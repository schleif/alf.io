/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.v2.user;

import alfio.controller.api.support.TicketHelper;
import alfio.controller.api.v2.model.ReservationInfo;
import alfio.controller.api.v2.model.ReservationInfo.TicketsByTicketCategory;
import alfio.controller.api.v2.model.ReservationPaymentResult;
import alfio.controller.api.v2.model.ReservationStatusInfo;
import alfio.controller.api.v2.model.ValidatedResponse;
import alfio.controller.form.ContactAndTicketsForm;
import alfio.controller.form.PaymentForm;
import alfio.controller.support.TemplateProcessor;
import alfio.manager.*;
import alfio.manager.payment.PaymentSpecification;
import alfio.manager.payment.StripeCreditCardManager;
import alfio.manager.support.PaymentResult;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.ReservationPriceCalculator;
import alfio.model.*;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.repository.AdditionalServiceItemRepository;
import alfio.repository.EventRepository;
import alfio.repository.TicketFieldRepository;
import alfio.repository.TicketReservationRepository;
import alfio.util.*;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static alfio.model.PriceContainer.VatStatus.*;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.util.MonetaryUtil.unitToCents;

@RestController
@AllArgsConstructor
@RequestMapping("/api/v2/public/")
@Log4j2
public class ReservationApiV2Controller {

    private final EventManager eventManager;
    private final EventRepository eventRepository;
    private final TicketReservationManager ticketReservationManager;
    private final TicketReservationRepository ticketReservationRepository;
    private final TicketFieldRepository ticketFieldRepository;
    private final MessageSource messageSource;
    private final ConfigurationManager configurationManager;
    private final PaymentManager paymentManager;
    private final FileUploadManager fileUploadManager;
    private final TemplateManager templateManager;
    private final ExtensionManager extensionManager;
    private final TicketHelper ticketHelper;
    private final EuVatChecker vatChecker;
    private final RecaptchaService recaptchaService;
    private final AdditionalServiceItemRepository additionalServiceItemRepository;

    /**
     * Note: now it will return for any states of the reservation.
     *
     * @param eventName
     * @param reservationId
     * @return
     */
    @GetMapping("/event/{eventName}/reservation/{reservationId}")
    public ResponseEntity<ReservationInfo> getReservationInfo(@PathVariable("eventName") String eventName,
                                                              @PathVariable("reservationId") String reservationId) {

        Optional<ReservationInfo> res = eventRepository.findOptionalByShortName(eventName).flatMap(event -> ticketReservationManager.findById(reservationId).flatMap(reservation -> {

            var orderSummary = ticketReservationManager.orderSummaryForReservationId(reservationId, event);

            var tickets = ticketReservationManager.findTicketsInReservation(reservationId);

            var ticketIds = tickets.stream().map(Ticket::getId).collect(Collectors.toSet());

            var descriptionsByTicketFieldId = ticketFieldRepository.findDescriptions(event.getShortName())
                .stream()
                .collect(Collectors.groupingBy(TicketFieldDescription::getTicketFieldConfigurationId));

            var valuesByTicketIds = ticketFieldRepository.findAllValuesByTicketIds(ticketIds)
                .stream()
                .collect(Collectors.groupingBy(TicketFieldValue::getTicketId));

            // check if the user can cancel ticket
            boolean hasPaidSupplement = ticketReservationManager.hasPaidSupplements(reservationId);
            //

            var ticketFieldsFilterer = getTicketFieldsFilterer(reservationId, event);

            //TODO: cleanup this transformation, we most likely don't need to fully load the ticket category
            var ticketsInReservation = tickets.stream()
                .collect(Collectors.groupingBy(Ticket::getCategoryId))
                .entrySet()
                .stream()
                .map(e -> {
                    var tc = eventManager.getTicketCategoryById(e.getKey(), event.getId());
                    var ts = e.getValue().stream().map(t -> {//


                        // TODO: n+1, should be cleaned up! see TicketDecorator.getCancellationEnabled
                        boolean cancellationEnabled = t.getFinalPriceCts() == 0 &&
                            (!hasPaidSupplement && configurationManager.getBooleanConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), t.getCategoryId(), ALLOW_FREE_TICKETS_CANCELLATION), false)) && // freeCancellationEnabled
                            eventManager.checkTicketCancellationPrerequisites().apply(t); // cancellationPrerequisitesMet
                        //
                        return toBookingInfoTicket(t, cancellationEnabled, ticketFieldsFilterer.getFieldsForTicket(t.getUuid()), descriptionsByTicketFieldId, valuesByTicketIds.getOrDefault(t.getId(), Collections.emptyList()));
                    }).collect(Collectors.toList());
                    return new TicketsByTicketCategory(tc.getName(), ts);
                })
                .collect(Collectors.toList());

            //
            var additionalInfo = ticketReservationRepository.getAdditionalInfo(reservationId);

            var italianInvoicing = additionalInfo.getInvoicingAdditionalInfo().getItalianEInvoicing() == null ?
                new TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing(null, null, null, null) :
                additionalInfo.getInvoicingAdditionalInfo().getItalianEInvoicing();
            //


            var shortReservationId =  ticketReservationManager.getShortReservationID(event, reservation);

            var formattedExpirationDate = reservation.getValidity() != null ? formatDateForLocales(event, ZonedDateTime.ofInstant(reservation.getValidity().toInstant(), event.getZoneId()), "datetime.pattern") : null;

            var paymentToken = paymentManager.getPaymentToken(reservationId);
            boolean tokenAcquired = paymentToken.isPresent();
            PaymentProxy selectedPaymentProxy = paymentToken.map(PaymentToken::getPaymentProvider).orElse(null);

            //
            var containsCategoriesLinkedToGroups = ticketReservationManager.containsCategoriesLinkedToGroups(reservationId, event.getId());
            //


            return Optional.of(new ReservationInfo(reservation.getId(), shortReservationId,
                reservation.getFirstName(), reservation.getLastName(), reservation.getEmail(),
                reservation.getValidity().getTime(),
                ticketsInReservation, new ReservationInfo.ReservationInfoOrderSummary(orderSummary), reservation.getStatus(),
                additionalInfo.hasBeenValidated(),
                formattedExpirationDate,
                reservation.getInvoiceNumber(),
                reservation.isInvoiceRequested(),
                reservation.getHasInvoiceOrReceiptDocument(),
                reservation.getHasBeenPaid(),
                tokenAcquired,
                selectedPaymentProxy != null ? selectedPaymentProxy : reservation.getPaymentMethod(),
                //
                additionalInfo.getAddCompanyBillingDetails(),
                additionalInfo.getBillingAddressCompany(),
                additionalInfo.getBillingAddressLine1(),
                additionalInfo.getBillingAddressLine2(),
                additionalInfo.getBillingAddressZip(),
                additionalInfo.getBillingAddressCity(),
                reservation.getVatCountryCode(),
                reservation.getCustomerReference(),
                reservation.getVatNr(),
                additionalInfo.getSkipVatNr(),
                //
                italianInvoicing.getFiscalCode(),
                italianInvoicing.getReferenceType(),
                italianInvoicing.getAddresseeCode(),
                italianInvoicing.getPec(),
                //
                containsCategoriesLinkedToGroups
                ));
        }));

        //
        return res.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Validator.TicketFieldsFilterer getTicketFieldsFilterer(String reservationId, EventAndOrganizationId event) {
        var fields = ticketFieldRepository.findAdditionalFieldsForEvent(event.getId());
        return new Validator.TicketFieldsFilterer(fields, ticketHelper.getTicketUUIDToCategoryId(),
            new HashSet<>(additionalServiceItemRepository.findAdditionalServiceIdsByReservationUuid(reservationId)),
            ticketReservationManager.findFirstInReservation(reservationId));
    }

    @GetMapping("/event/{eventName}/reservation/{reservationId}/status")
    public ResponseEntity<ReservationStatusInfo> getReservationStatus(@PathVariable("eventName") String eventName,
                                                                      @PathVariable("reservationId") String reservationId) {

        Optional<ReservationStatusInfo> res = Optional.empty();
        if (eventRepository.existsByShortName(eventName)) {
            res = ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
                .map(status -> new ReservationStatusInfo(status.getStatus(), Boolean.TRUE.equals(status.getValidated())));
        }

        return res.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }


    @DeleteMapping("/event/{eventName}/reservation/{reservationId}")
    public ResponseEntity<Boolean> cancelPendingReservation(@PathVariable("eventName") String eventName,
                                                            @PathVariable("reservationId") String reservationId) {

        getReservationWithPendingStatus(eventName, reservationId)
            .ifPresent(er -> ticketReservationManager.cancelPendingReservation(reservationId, false, null));
        return ResponseEntity.ok(true);
    }

    @PostMapping("/event/{eventName}/reservation/{reservationId}/back-to-booking")
    public ResponseEntity<Boolean> backToBooking(@PathVariable("eventName") String eventName,
                                                 @PathVariable("reservationId") String reservationId) {

        getReservationWithPendingStatus(eventName, reservationId)
            .ifPresent(er -> ticketReservationRepository.updateValidationStatus(reservationId, false));


        return ResponseEntity.ok(true);
    }

    @PostMapping("/event/{eventName}/reservation/{reservationId}")
    public ResponseEntity<ValidatedResponse<ReservationPaymentResult>> confirmOverview(@PathVariable("eventName") String eventName,
                                                                                       @PathVariable("reservationId") String reservationId,
                                                                                       @RequestParam("lang") String lang,
                                                                                       @RequestBody  PaymentForm paymentForm,
                                                                                       BindingResult bindingResult,
                                                                                       HttpServletRequest request) {

        return getReservation(eventName, reservationId).map(er -> {

           var event = er.getLeft();
           var ticketReservation = er.getRight();
           var locale = LocaleUtil.forLanguageTag(lang, event);

            if (!ticketReservation.getValidity().after(new Date())) {
                bindingResult.reject(ErrorsCode.STEP_2_ORDER_EXPIRED);
            }


            final TotalPrice reservationCost = ticketReservationManager.totalReservationCostWithVAT(reservationId);

            paymentForm.validate(bindingResult, event, reservationCost);
            if (bindingResult.hasErrors()) {
                return buildReservationPaymentStatus(bindingResult);
            }

            if(isCaptchaInvalid(reservationCost.getPriceWithVAT(), paymentForm.getPaymentMethod(), request, event)) {
                log.debug("captcha validation failed.");
                bindingResult.reject(ErrorsCode.STEP_2_CAPTCHA_VALIDATION_FAILED);
            }

            if(!bindingResult.hasErrors()) {
                extensionManager.handleReservationValidation(event, ticketReservation, paymentForm, bindingResult);
            }

            if (bindingResult.hasErrors()) {
                return buildReservationPaymentStatus(bindingResult);
            }

            CustomerName customerName = new CustomerName(ticketReservation.getFullName(), ticketReservation.getFirstName(), ticketReservation.getLastName(), event.mustUseFirstAndLastName());

            OrderSummary orderSummary = ticketReservationManager.orderSummaryForReservationId(reservationId, event);

            PaymentToken paymentToken = paymentManager.getPaymentToken(reservationId).orElse(null);
            if(paymentToken == null && StringUtils.isNotEmpty(paymentForm.getGatewayToken())) {
                paymentToken = paymentManager.buildPaymentToken(paymentForm.getGatewayToken(), paymentForm.getPaymentMethod(), new PaymentContext(event, reservationId));
            }
            PaymentSpecification spec = new PaymentSpecification(reservationId, paymentToken, reservationCost.getPriceWithVAT(),
                event, ticketReservation.getEmail(), customerName, ticketReservation.getBillingAddress(), ticketReservation.getCustomerReference(),
                locale, ticketReservation.isInvoiceRequested(), !ticketReservation.isDirectAssignmentRequested(),
                orderSummary, ticketReservation.getVatCountryCode(), ticketReservation.getVatNr(), ticketReservation.getVatStatus(),
                Boolean.TRUE.equals(paymentForm.getTermAndConditionsAccepted()), Boolean.TRUE.equals(paymentForm.getPrivacyPolicyAccepted()));

            final PaymentResult status = ticketReservationManager.performPayment(spec, reservationCost, Optional.ofNullable(paymentForm.getPaymentMethod()));


            if (status.isRedirect()) {
                var body = ValidatedResponse.toResponse(bindingResult,
                    new ReservationPaymentResult(!bindingResult.hasErrors(), true, status.getRedirectUrl(), status.isFailed(), status.getGatewayIdOrNull()));
                return ResponseEntity.ok(body);
            }

            if (!status.isSuccessful()) {
                String errorMessageCode = status.getErrorCode().orElse(StripeCreditCardManager.STRIPE_UNEXPECTED);
                MessageSourceResolvable message = new DefaultMessageSourceResolvable(new String[]{errorMessageCode, StripeCreditCardManager.STRIPE_UNEXPECTED});
                bindingResult.reject(ErrorsCode.STEP_2_PAYMENT_PROCESSING_ERROR, new Object[]{messageSource.getMessage(message, locale)}, null);
                return buildReservationPaymentStatus(bindingResult);
            }


            return buildReservationPaymentStatus(bindingResult);

        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<ValidatedResponse<ReservationPaymentResult>> buildReservationPaymentStatus(BindingResult bindingResult) {
        var body = ValidatedResponse.toResponse(bindingResult, new ReservationPaymentResult(!bindingResult.hasErrors(), false, null, true, null));
        return ResponseEntity.status(bindingResult.hasErrors() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK).body(body);
    }

    @PostMapping("/event/{eventName}/reservation/{reservationId}/validate-to-overview")
    public ResponseEntity<ValidatedResponse<Boolean>> validateToOverview(@PathVariable("eventName") String eventName,
                                                                         @PathVariable("reservationId") String reservationId,
                                                                         @RequestParam("lang") String lang,
                                                                         @RequestBody ContactAndTicketsForm contactAndTicketsForm,
                                                                         BindingResult bindingResult) {


        return getReservationWithPendingStatus(eventName, reservationId).map(er -> {
            var event = er.getLeft();
            var reservation = er.getRight();
            var locale = LocaleUtil.forLanguageTag(lang, event);
            final TotalPrice reservationCost = ticketReservationManager.totalReservationCostWithVAT(reservation.withVatStatus(event.getVatStatus()));
            boolean forceAssignment = configurationManager.getFor(event, FORCE_TICKET_OWNER_ASSIGNMENT_AT_RESERVATION).getValueAsBooleanOrDefault(false);

            if(forceAssignment || ticketReservationManager.containsCategoriesLinkedToGroups(reservationId, event.getId())) {
                contactAndTicketsForm.setPostponeAssignment(false);
            }

            boolean invoiceOnly = configurationManager.isInvoiceOnly(event);

            if(invoiceOnly && reservationCost.getPriceWithVAT() > 0) {
                //override, that's why we save it
                contactAndTicketsForm.setInvoiceRequested(true);
            }

            CustomerName customerName = new CustomerName(contactAndTicketsForm.getFullName(), contactAndTicketsForm.getFirstName(), contactAndTicketsForm.getLastName(), event.mustUseFirstAndLastName(), false);


            ticketReservationRepository.resetVat(reservationId, contactAndTicketsForm.isInvoiceRequested(), event.getVatStatus(),
                reservation.getSrcPriceCts(), reservationCost.getPriceWithVAT(), reservationCost.getVAT(), Math.abs(reservationCost.getDiscount()), reservation.getCurrencyCode());
            if(contactAndTicketsForm.isBusiness()) {
                checkAndApplyVATRules(eventName, reservationId, contactAndTicketsForm, bindingResult, event);
            }

            //persist data
            ticketReservationManager.updateReservation(reservationId, customerName, contactAndTicketsForm.getEmail(),
                contactAndTicketsForm.getBillingAddressCompany(), contactAndTicketsForm.getBillingAddressLine1(), contactAndTicketsForm.getBillingAddressLine2(),
                contactAndTicketsForm.getBillingAddressZip(), contactAndTicketsForm.getBillingAddressCity(), contactAndTicketsForm.getVatCountryCode(),
                contactAndTicketsForm.getCustomerReference(), contactAndTicketsForm.getVatNr(), contactAndTicketsForm.isInvoiceRequested(),
                contactAndTicketsForm.getAddCompanyBillingDetails(), contactAndTicketsForm.canSkipVatNrCheck(), false, locale);

            boolean italyEInvoicing = configurationManager.getFor(event, ENABLE_ITALY_E_INVOICING).getValueAsBooleanOrDefault(false);

            if(italyEInvoicing) {
                ticketReservationManager.updateReservationInvoicingAdditionalInformation(reservationId,
                    new TicketReservationInvoicingAdditionalInfo(getItalianInvoicingInfo(contactAndTicketsForm))
                );
            }

            assignTickets(event.getShortName(), reservationId, contactAndTicketsForm, bindingResult, locale, true, true);
            //

            Map<ConfigurationKeys, Boolean> formValidationParameters = Collections.singletonMap(ENABLE_ITALY_E_INVOICING, italyEInvoicing);

            var ticketFieldFilterer = getTicketFieldsFilterer(reservationId, event);
            //
            contactAndTicketsForm.validate(bindingResult, event,
                new SameCountryValidator(configurationManager, extensionManager, event, reservationId, vatChecker),
                formValidationParameters, ticketFieldFilterer);
            //

            if(!bindingResult.hasErrors()) {
                extensionManager.handleReservationValidation(event, reservation, contactAndTicketsForm, bindingResult);
            }

            if(!bindingResult.hasErrors()) {
                ticketReservationRepository.updateValidationStatus(reservationId, true);
            }

            var body = ValidatedResponse.toResponse(bindingResult, !bindingResult.hasErrors());
            return ResponseEntity.status(bindingResult.hasErrors() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK).body(body);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing getItalianInvoicingInfo(ContactAndTicketsForm contactAndTicketsForm) {
        if("IT".equalsIgnoreCase(contactAndTicketsForm.getVatCountryCode())) {
            return new TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing(contactAndTicketsForm.getItalyEInvoicingFiscalCode(),
                contactAndTicketsForm.getItalyEInvoicingReferenceType(),
                contactAndTicketsForm.getItalyEInvoicingReferenceAddresseeCode(),
                contactAndTicketsForm.getItalyEInvoicingReferencePEC());
        }
        return null;
    }

    private void assignTickets(String eventName, String reservationId, ContactAndTicketsForm contactAndTicketsForm, BindingResult bindingResult, Locale locale, boolean preAssign, boolean skipValidation) {
        if(!contactAndTicketsForm.isPostponeAssignment()) {
            contactAndTicketsForm.getTickets().forEach((ticketId, owner) -> {
                if (preAssign) {
                    Optional<Errors> bindingResultOptional = skipValidation ? Optional.empty() : Optional.of(bindingResult);
                    ticketHelper.preAssignTicket(eventName, reservationId, ticketId, owner, bindingResultOptional, locale, Optional.empty());
                } else {
                    ticketHelper.assignTicket(eventName, ticketId, owner, Optional.of(bindingResult), locale, Optional.empty(), true);
                }
            });
        }
    }

    private void checkAndApplyVATRules(String eventName, String reservationId, ContactAndTicketsForm contactAndTicketsForm, BindingResult bindingResult, Event event) {
        // VAT handling
        String country = contactAndTicketsForm.getVatCountryCode();

        // validate VAT presence if EU mode is enabled
        if(vatChecker.isReverseChargeEnabledFor(event) && isEUCountry(country)) {
            ValidationUtils.rejectIfEmptyOrWhitespace(bindingResult, "vatNr", "error.emptyField");
        }

        try {
            Optional<VatDetail> vatDetail = eventRepository.findOptionalByShortName(eventName)
                .flatMap(e -> ticketReservationRepository.findOptionalReservationById(reservationId).map(r -> Pair.of(e, r)))
                .filter(e -> EnumSet.of(INCLUDED, NOT_INCLUDED).contains(e.getKey().getVatStatus()))
                .filter(e -> vatChecker.isReverseChargeEnabledFor(e.getKey()))
                .flatMap(e -> vatChecker.checkVat(contactAndTicketsForm.getVatNr(), country, e.getKey()));


            vatDetail.ifPresent(vatValidation -> {
                if (!vatValidation.isValid()) {
                    bindingResult.rejectValue("vatNr", "error.vat");
                } else {
                    var reservation = ticketReservationManager.findById(reservationId).orElseThrow();
                    var currencyCode = reservation.getCurrencyCode();
                    PriceContainer.VatStatus vatStatus = determineVatStatus(event.getVatStatus(), vatValidation.isVatExempt());
                    var updatedPrice = ticketReservationManager.totalReservationCostWithVAT(reservation.withVatStatus(vatStatus));// update VatStatus to the new value for calculating the new price
                    var calculator = new ReservationPriceCalculator(reservation.withVatStatus(vatStatus), updatedPrice, ticketReservationManager.findTicketsInReservation(reservationId), event);
                    ticketReservationRepository.updateBillingData(vatStatus, reservation.getSrcPriceCts(),
                        unitToCents(calculator.getFinalPrice(), currencyCode), unitToCents(calculator.getVAT(), currencyCode), unitToCents(calculator.getAppliedDiscount(), currencyCode),
                        reservation.getCurrencyCode(), StringUtils.trimToNull(vatValidation.getVatNr()),
                        country, contactAndTicketsForm.isInvoiceRequested(), reservationId);
                    vatChecker.logSuccessfulValidation(vatValidation, reservationId, event.getId());
                }
            });
        } catch (IllegalStateException ise) {//vat checker failure
            bindingResult.rejectValue("vatNr", "error.vatVIESDown");
        }
    }

    private Optional<Pair<Event, TicketReservation>> getReservation(String eventName, String reservationId) {
        return eventRepository.findOptionalByShortName(eventName)
            .flatMap(event -> ticketReservationManager.findById(reservationId)
                .flatMap(reservation -> Optional.of(Pair.of(event, reservation))));
    }

    private Optional<Pair<Event, TicketReservation>> getReservationWithPendingStatus(String eventName, String reservationId) {
        return eventRepository.findOptionalByShortName(eventName)
            .flatMap(event -> ticketReservationManager.findById(reservationId)
                .filter(reservation -> reservation.getStatus() == TicketReservation.TicketReservationStatus.PENDING)
                .flatMap(reservation -> Optional.of(Pair.of(event, reservation))));
    }

    @PostMapping("/event/{eventName}/reservation/{reservationId}/re-send-email")
    public ResponseEntity<Boolean> reSendReservationConfirmationEmail(@PathVariable("eventName") String eventName,
                                                                      @PathVariable("reservationId") String reservationId,
                                                                      @RequestParam("lang") String lang) {



        var res = eventRepository.findOptionalByShortName(eventName).map(event ->
            ticketReservationManager.findById(reservationId).map(ticketReservation -> {
                ticketReservationManager.sendConfirmationEmail(event, ticketReservation, LocaleUtil.forLanguageTag(lang, event));
                return true;
            }).orElse(false)
        ).orElse(false);

        return ResponseEntity.ok(res);
    }


    //
    @GetMapping("/event/{eventName}/reservation/{reservationId}/receipt")
    public ResponseEntity<Void> getReceipt(@PathVariable("eventName") String eventName,
                                           @PathVariable("reservationId") String reservationId,
                                           HttpServletResponse response,
                                           Authentication authentication) {
        return handleReservationWith(eventName, reservationId, authentication, generatePdfFunction(false, response));
    }

    @GetMapping("/event/{eventName}/reservation/{reservationId}/invoice")
    public ResponseEntity<Void> getInvoice(@PathVariable("eventName") String eventName,
                                           @PathVariable("reservationId") String reservationId,
                                           HttpServletResponse response,
                                           Authentication authentication) {
        return handleReservationWith(eventName, reservationId, authentication, generatePdfFunction(true, response));
    }
    //

    private ResponseEntity<Void> handleReservationWith(String eventName, String reservationId, Authentication authentication,
                                                       BiFunction<Event, TicketReservation, ResponseEntity<Void>> with) {
        ResponseEntity<Void> notFound = ResponseEntity.notFound().build();
        ResponseEntity<Void> badRequest = ResponseEntity.badRequest().build();



        return eventRepository.findOptionalByShortName(eventName).map(event -> {
                if(canAccessReceiptOrInvoice(event, authentication)) {
                    return ticketReservationManager.findById(reservationId).map(ticketReservation -> with.apply(event, ticketReservation)).orElse(notFound);
                } else {
                    return badRequest;
                }
            }
        ).orElse(notFound);
    }

    private boolean canAccessReceiptOrInvoice(EventAndOrganizationId event, Authentication authentication) {
        return configurationManager.canGenerateReceiptOrInvoiceToCustomer(event) || !isAnonymous(authentication);
    }


    private boolean isAnonymous(Authentication authentication) {
        return authentication == null ||
            authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_ANONYMOUS"::equals);
    }

    private BiFunction<Event, TicketReservation, ResponseEntity<Void>> generatePdfFunction(boolean forInvoice, HttpServletResponse response) {
        return (event, reservation) -> {
            if(forInvoice ^ reservation.getInvoiceNumber() != null || reservation.isCancelled()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> billingModel = ticketReservationManager.getOrCreateBillingDocumentModel(event, reservation, null);

            try {
                FileUtil.sendHeaders(response, event.getShortName(), reservation.getId(), forInvoice ? "invoice" : "receipt");
                TemplateProcessor.buildReceiptOrInvoicePdf(event, fileUploadManager, LocaleUtil.forLanguageTag(reservation.getUserLanguage()),
                    templateManager, billingModel, forInvoice ? TemplateResource.INVOICE_PDF : TemplateResource.RECEIPT_PDF,
                    extensionManager, response.getOutputStream());
                return ResponseEntity.ok(null);
            } catch (IOException ioe) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        };
    }
    //----------------


    @PostMapping("/event/{eventName}/reservation/{reservationId}/payment/{method}/init")
    public ResponseEntity<TransactionInitializationToken> initTransaction(@PathVariable("eventName") String eventName,
                                                                          @PathVariable("reservationId") String reservationId,
                                                                          @PathVariable("method") String paymentMethodStr,
                                                                          @RequestParam MultiValueMap<String, String> allParams) {
        var paymentMethod = PaymentMethod.safeParse(paymentMethodStr);

        if(paymentMethod == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<ResponseEntity<TransactionInitializationToken>> responseEntity = getEventReservationPair(eventName, reservationId)
            .map(pair -> {
                var event = pair.getLeft();
                return ticketReservationManager.initTransaction(event, reservationId, paymentMethod, allParams)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
            });
        return responseEntity.orElseGet(() -> ResponseEntity.badRequest().build());
    }

    @DeleteMapping("/event/{eventName}/reservation/{reservationId}/payment/token")
    public ResponseEntity<Boolean> removeToken(@PathVariable("eventName") String eventName,
                                               @PathVariable("reservationId") String reservationId) {

        var res = getEventReservationPair(eventName, reservationId).map(et -> paymentManager.removePaymentTokenReservation(et.getRight().getId())).orElse(false);
        return ResponseEntity.ok(res);
    }

    private Optional<Pair<Event, TicketReservation>> getEventReservationPair(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId) {
        return eventRepository.findOptionalByShortName(eventName)
            .map(event -> Pair.of(event, ticketReservationManager.findById(reservationId)))
            .filter(pair -> pair.getRight().isPresent())
            .map(pair -> Pair.of(pair.getLeft(), pair.getRight().orElseThrow()));
    }

    @GetMapping("/event/{eventName}/reservation/{reservationId}/payment/{method}/status")
    public ResponseEntity<ReservationPaymentResult> getTransactionStatus(@PathVariable("eventName") String eventName,
                                                              @PathVariable("reservationId") String reservationId,
                                                              @PathVariable("method") String paymentMethodStr) {

        var paymentMethod = PaymentMethod.safeParse(paymentMethodStr);

        if(paymentMethod == null) {
            return ResponseEntity.badRequest().build();
        }

        return getEventReservationPair(eventName, reservationId)
            .flatMap(pair -> paymentManager.getTransactionStatus(pair.getRight(), paymentMethod))
            .map(pr -> ResponseEntity.ok(new ReservationPaymentResult(pr.isSuccessful(), pr.isRedirect(), pr.getRedirectUrl(), pr.isFailed(), pr.getGatewayIdOrNull())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }


    private static ReservationInfo.AdditionalField toAdditionalField(TicketFieldConfigurationDescriptionAndValue t, Map<String, ReservationInfo.Description> description) {
        var fields = t.getFields().stream().map(f -> new ReservationInfo.Field(f.getFieldIndex(), f.getFieldValue())).collect(Collectors.toList());
        return new ReservationInfo.AdditionalField(t.getName(), t.getValue(), t.getType(), t.isRequired(),
            t.getMinLength(), t.getMaxLength(), t.getRestrictedValues(),
            fields, t.isBeforeStandardFields(), description);
    }

    private static Map<String, ReservationInfo.Description> fromFieldDescriptions(List<TicketFieldDescription> descs) {
        return descs.stream().collect(Collectors.toMap(TicketFieldDescription::getLocale,
            d -> new ReservationInfo.Description(d.getLabelDescription(), d.getPlaceholderDescription(), d.getRestrictedValuesDescription())));
    }

    private static ReservationInfo.BookingInfoTicket toBookingInfoTicket(Ticket ticket,
                                                                         boolean cancellationEnabled,
                                                                         List<TicketFieldConfiguration> ticketFields,
                                                                         Map<Integer, List<TicketFieldDescription>> descriptionsByTicketFieldId,
                                                                         List<TicketFieldValue> ticketFieldValues) {


        var valueById = ticketFieldValues.stream().collect(Collectors.toMap(TicketFieldValue::getTicketFieldConfigurationId, Function.identity()));


        var tfcdav = ticketFields.stream()
            .sorted(Comparator.comparing(TicketFieldConfiguration::getOrder))
            .map(tfc -> {
                var tfd = descriptionsByTicketFieldId.get(tfc.getId()).get(0);//take first, temporary!
                var fieldValue = valueById.get(tfc.getId());
                var t = new TicketFieldConfigurationDescriptionAndValue(tfc, tfd, 1, fieldValue == null ? null : fieldValue.getValue());
                var descs = fromFieldDescriptions(descriptionsByTicketFieldId.get(t.getTicketFieldConfigurationId()));
                return toAdditionalField(t, descs);
            }).collect(Collectors.toList());

        return new ReservationInfo.BookingInfoTicket(ticket.getUuid(),
            ticket.getFirstName(), ticket.getLastName(),
            ticket.getEmail(), ticket.getFullName(),
            ticket.getUserLanguage(),
            ticket.getAssigned(), ticket.getLockedAssignment(), cancellationEnabled, tfcdav);
    }

    private Map<String, String> formatDateForLocales(Event event, ZonedDateTime date, String formattingCode) {

        Map<String, String> res = new HashMap<>();
        for (ContentLanguage cl : event.getContentLanguages()) {
            var formatter = messageSource.getMessage(formattingCode, null, cl.getLocale());
            res.put(cl.getLocale().getLanguage(), DateTimeFormatter.ofPattern(formatter, cl.getLocale()).format(date));
        }
        return res;
    }

    private boolean isEUCountry(String countryCode) {
        return configurationManager.getFor(EU_COUNTRIES_LIST).getRequiredValue().contains(countryCode);
    }

    private static PriceContainer.VatStatus determineVatStatus(PriceContainer.VatStatus current, boolean isVatExempt) {
        if(!isVatExempt) {
            return current;
        }
        return current == NOT_INCLUDED ? NOT_INCLUDED_EXEMPT : INCLUDED_EXEMPT;
    }


    private boolean isCaptchaInvalid(int cost, PaymentProxy paymentMethod, HttpServletRequest request, EventAndOrganizationId event) {
        return (cost == 0 || paymentMethod == PaymentProxy.OFFLINE || paymentMethod == PaymentProxy.ON_SITE)
            && configurationManager.isRecaptchaForOfflinePaymentEnabled(event)
            && !recaptchaService.checkRecaptcha(null, request);
    }
}
