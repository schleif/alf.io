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
package alfio.controller.api.admin;

import alfio.controller.api.support.EventListItem;
import alfio.controller.api.support.PageAndContent;
import alfio.controller.api.support.TicketHelper;
import alfio.controller.support.TemplateProcessor;
import alfio.manager.*;
import alfio.manager.i18n.I18nManager;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.*;
import alfio.model.TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing;
import alfio.model.modification.*;
import alfio.model.result.ValidationResult;
import alfio.model.transaction.Transaction;
import alfio.model.user.Organization;
import alfio.model.user.Role;
import alfio.model.user.User;
import alfio.repository.DynamicFieldTemplateRepository;
import alfio.repository.EventDescriptionRepository;
import alfio.repository.SponsorScanRepository;
import alfio.repository.TicketFieldRepository;
import alfio.util.*;
import com.opencsv.CSVReader;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static alfio.util.Validator.*;
import static alfio.util.Wrappers.optionally;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.defaultString;

@RestController
@RequestMapping("/admin/api")
@Log4j2
@AllArgsConstructor
public class EventApiController {

    private static final String OK = "OK";
    private static final String CUSTOM_FIELDS_PREFIX = "custom:";
    private final EventManager eventManager;
    private final EventStatisticsManager eventStatisticsManager;
    private final I18nManager i18nManager;
    private final TicketReservationManager ticketReservationManager;
    private final TicketFieldRepository ticketFieldRepository;
    private final EventDescriptionRepository eventDescriptionRepository;
    private final TicketHelper ticketHelper;
    private final DynamicFieldTemplateRepository dynamicFieldTemplateRepository;
    private final UserManager userManager;
    private final SponsorScanRepository sponsorScanRepository;
    private final PaymentManager paymentManager;
    private final TemplateManager templateManager;
    private final FileUploadManager fileUploadManager;
    private final ConfigurationManager configurationManager;
    private final ExtensionManager extensionManager;


    @ExceptionHandler(DataAccessException.class)
    public String exception(DataAccessException e) {
        log.warn("unhandled exception", e);
        return "unexpected error. More info in the application log";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unhandledException(Exception e) {
        log.warn("unhandled exception", e);
        return e.getMessage();
    }


    @GetMapping("/paymentProxies/{organizationId}")
    @ResponseStatus(HttpStatus.OK)
    public List<PaymentManager.PaymentMethodDTO> getPaymentProxies( @PathVariable("organizationId") int organizationId, Principal principal) {
        return userManager.findUserOrganizations(principal.getName())
            .stream()
            .filter(o -> o.getId() == organizationId)
            .findFirst()
            .map(o -> paymentManager.getPaymentMethods(o.getId()))
            .orElse(Collections.emptyList());
    }

    @GetMapping(value = "/events", headers = "Authorization")
    public List<EventListItem> getAllEventsForExternal(Principal principal, HttpServletRequest request) {
        List<Integer> userOrganizations = userManager.findUserOrganizations(principal.getName()).stream().map(Organization::getId).collect(toList());
        return eventManager.getActiveEvents().stream()
            .filter(e -> userOrganizations.contains(e.getOrganizationId()))
            .sorted(Comparator.comparing(e -> e.getBegin().withZoneSameInstant(ZoneId.systemDefault())))
            .map(s -> new EventListItem(s, request.getContextPath(), eventDescriptionRepository.findByEventId(s.getId())))
            .collect(toList());
    }

    @GetMapping("/events")
    public List<EventStatistic> getAllEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatistics(principal.getName());
    }

    @GetMapping("/active-events")
    public List<EventStatistic> getAllActiveEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatisticsFilteredBy(principal.getName(), event -> !event.expiredSince(14));
    }

    @GetMapping("/expired-events")
    public List<EventStatistic> getAllExpiredEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatisticsFilteredBy(principal.getName(), event -> event.expiredSince(14));
    }


    @AllArgsConstructor
    @Getter
    public static class EventAndOrganization {
        private final EventWithAdditionalInfo event;
        private final Organization organization;
    }


    @GetMapping("/events/{name}")
    public ResponseEntity<EventAndOrganization> getSingleEvent(@PathVariable("name") String eventName, Principal principal) {
        final String username = principal.getName();
        return optionally(() -> eventStatisticsManager.getEventWithAdditionalInfo(eventName, username))
            .map(event -> {
                EventAndOrganization out = new EventAndOrganization(event, eventManager.loadOrganizer(event.getEvent(), username));
                return ResponseEntity.ok(out);
            }).orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }
    
    @DeleteMapping("/events/{eventId}")
    public void deleteEvent(@PathVariable("eventId") int eventId, Principal principal) {
    	eventManager.deleteEvent(eventId, principal.getName());
    }

    @GetMapping("/events/id/{eventId}")
    public Event getSingleEventById(@PathVariable("eventId") int eventId, Principal principal) {
        return eventManager.getSingleEventById(eventId, principal.getName());
    }

    @PostMapping("/events/check")
    public ValidationResult validateEventRequest(@RequestBody EventModification eventModification, Errors errors) {
        return validateEvent(eventModification,errors);
    }

    public static ValidationResult validateEvent(EventModification eventModification, Errors errors) {
        ValidationResult base = validateEventHeader(Optional.empty(), eventModification, errors)
            .or(validateEventDates(eventModification, errors))
            .or(validateTicketCategories(eventModification, errors))
            .or(validateEventPrices(Optional.empty(), eventModification, errors))
            .or(eventModification.getAdditionalServices().stream().map(as -> validateAdditionalService(as, eventModification, errors)).reduce(ValidationResult::or).orElse(ValidationResult.success()));
        AtomicInteger counter = new AtomicInteger();
        return base.or(eventModification.getTicketCategories().stream()
                .map(c -> validateCategory(c, errors, "ticketCategories[" + counter.getAndIncrement() + "].", eventModification))
                .reduce(ValidationResult::or)
                .orElse(ValidationResult.success()))
            .or(validateAdditionalTicketFields(eventModification.getTicketFields(), errors));
    }


    private static ValidationResult validateAdditionalTicketFields(List<EventModification.AdditionalField> ticketFields, Errors errors) {
        //meh
        AtomicInteger cnt = new AtomicInteger();
        return Optional.ofNullable(ticketFields).orElseGet(Collections::emptyList).stream().map(field -> {
            String prefix = "ticketFields["+cnt.getAndIncrement()+"]";
            if (StringUtils.isBlank(field.getName())) {
                errors.rejectValue(prefix + ".name", "error.required");
            }
            //TODO: check label value is present for all the locales
            //TODO: for select check option value+label

            return Validator.evaluateValidationResult(errors);
        }).reduce(ValidationResult::or).orElseGet(ValidationResult::success);
    }

    @PostMapping("/events/new")
    public String insertEvent(@RequestBody EventModification eventModification) {
        eventManager.createEvent(eventModification);
        return OK;
    }

    @PutMapping("/events/{id}/status")
    public String activateEvent(@PathVariable("id") int id, @RequestParam("active") boolean active, Principal principal) {
        eventManager.toggleActiveFlag(id, principal.getName(), active);
        return OK;
    }

    @PostMapping("/events/{id}/header/update")
    public ValidationResult updateHeader(@PathVariable("id") int id, @RequestBody EventModification eventModification, Errors errors,  Principal principal) {
        Event event = eventManager.getSingleEventById(id, principal.getName());
        return validateEventHeader(Optional.of(event), eventModification, errors).ifSuccess(() -> eventManager.updateEventHeader(event, eventModification, principal.getName()));
    }

    @PostMapping("/events/{id}/prices/update")
    public ValidationResult updatePrices(@PathVariable("id") int id, @RequestBody EventModification eventModification, Errors errors,  Principal principal) {
        Event event = eventManager.getSingleEventById(id, principal.getName());
        return validateEventPrices(Optional.of(event), eventModification, errors).ifSuccess(() -> eventManager.updateEventPrices(event, eventModification, principal.getName()));
    }

    @PostMapping("/events/{eventId}/categories/{categoryId}/update")
    public ValidationResult updateExistingCategory(@PathVariable("eventId") int eventId, @PathVariable("categoryId") int categoryId, @RequestBody TicketCategoryModification category, Errors errors, Principal principal) {
        return validateCategory(category, errors).ifSuccess(() -> eventManager.updateCategory(categoryId, eventId, category, principal.getName()));
    }

    @PostMapping("/events/{eventId}/categories/new")
    public ValidationResult createCategory(@PathVariable("eventId") int eventId, @RequestBody TicketCategoryModification category, Errors errors, Principal principal) {
        return validateCategory(category, errors).ifSuccess(() -> eventManager.insertCategory(eventId, category, principal.getName()));
    }
    
    @PutMapping("/events/reallocate")
    public String reallocateTickets(@RequestBody TicketAllocationModification form) {
        eventManager.reallocateTickets(form.getSrcCategoryId(), form.getTargetCategoryId(), form.getEventId());
        return OK;
    }

    @PutMapping("/events/{eventName}/category/{categoryId}/unbind-tickets")
    public String unbindTickets(@PathVariable("eventName") String eventName, @PathVariable("categoryId") int categoryId, Principal principal) {
        eventManager.unbindTickets(eventName, categoryId, principal.getName());
        return OK;
    }

    @DeleteMapping("/events/{eventName}/category/{categoryId}")
    public String deleteCategory(@PathVariable("eventName") String eventName, @PathVariable("categoryId") int categoryId, Principal principal) {
        eventManager.deleteCategory(eventName, categoryId, principal.getName());
        return OK;
    }

    private static final List<String> FIXED_FIELDS = Arrays.asList("ID", "Creation", "Category", "Event", "Status", "OriginalPrice", "PaidPrice", "Discount", "VAT", "ReservationID", "Full Name", "First Name", "Last Name", "E-Mail", "Locked", "Language", "Confirmation", "Billing Address", "Payment ID", "Payment Method");
    private static final List<SerializablePair<String, String>> FIXED_PAIRS = FIXED_FIELDS.stream().map(f -> SerializablePair.of(f, f)).collect(toList());
    private static final List<String> ITALIAN_E_INVOICING_FIELDS = List.of("Fiscal Code", "Reference Type", "Addressee Code", "PEC");

    @GetMapping("/events/{eventName}/export")
    public void downloadAllTicketsCSV(@PathVariable("eventName") String eventName, @RequestParam(name = "format", defaultValue = "excel") String format, HttpServletRequest request, HttpServletResponse response, Principal principal) throws IOException {
        List<String> fields = Arrays.asList(Optional.ofNullable(request.getParameterValues("fields")).orElse(new String[] {}));
        Event event = loadEvent(eventName, principal);
        Map<Integer, TicketCategory> categoriesMap = eventManager.loadTicketCategories(event).stream().collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
        ZoneId eventZoneId = event.getZoneId();

        if ("excel".equals(format)) {
            exportTicketExcel(event.getShortName(), response, principal, fields, categoriesMap, eventZoneId);
        } else {
            exportTicketCSV(event.getShortName(), response, principal, fields, categoriesMap, eventZoneId);
        }
    }

    private void exportTicketExcel(String eventName, HttpServletResponse response, Principal principal, List<String> fields, Map<Integer,TicketCategory> categoriesMap, ZoneId eventZoneId) throws IOException {
        ExportUtils.exportExcel(eventName + "-export.xlsx",
            eventName + " export",
            exportHeader(fields),
            exportLines(eventName, principal, fields, categoriesMap, eventZoneId), response);

    }

    private void exportTicketCSV(String eventName, HttpServletResponse response,
                           Principal principal, List<String> fields,
                           Map<Integer, TicketCategory> categoriesMap,
                           ZoneId eventZoneId) throws IOException {
        ExportUtils.exportCsv(eventName + "-export.csv", exportHeader(fields), exportLines(eventName, principal, fields, categoriesMap, eventZoneId), response);
    }

    private String[] exportHeader(List<String> fields) {
        return fields.stream().map(f -> {
            if(f.startsWith(CUSTOM_FIELDS_PREFIX)) {
                return f.substring(CUSTOM_FIELDS_PREFIX.length());
            }
            return f;
        }).toArray(String[]::new);
    }

    private Stream<String[]> exportLines(String eventName, Principal principal, List<String> fields, Map<Integer, TicketCategory> categoriesMap, ZoneId eventZoneId) {
        var username = principal.getName();
        var eInvoicingEnabled = configurationManager.isItalianEInvoicingEnabled(eventManager.getEventAndOrganizationId(eventName, username));

        return eventManager.findAllConfirmedTicketsForCSV(eventName, username).stream().map(trs -> {
            Ticket t = trs.getTicket();
            var currencyCode = t.getCurrencyCode();
            TicketReservation reservation = trs.getTicketReservation();
            List<String> line = new ArrayList<>();
            if(fields.contains("ID")) {line.add(t.getUuid());}
            if(fields.contains("Creation")) {line.add(t.getCreation().withZoneSameInstant(eventZoneId).toString());}
            if(fields.contains("Category")) {line.add(categoriesMap.get(t.getCategoryId()).getName());}
            if(fields.contains("Event")) {line.add(eventName);}
            if(fields.contains("Status")) {line.add(t.getStatus().toString());}
            if(fields.contains("OriginalPrice")) {line.add(MonetaryUtil.centsToUnit(t.getSrcPriceCts(), currencyCode).toString());}
            if(fields.contains("PaidPrice")) {line.add(MonetaryUtil.centsToUnit(t.getFinalPriceCts(), currencyCode).toString());}
            if(fields.contains("Discount")) {line.add(MonetaryUtil.centsToUnit(t.getDiscountCts(), currencyCode).toString());}
            if(fields.contains("VAT")) {line.add(MonetaryUtil.centsToUnit(t.getVatCts(), currencyCode).toString());}
            if(fields.contains("ReservationID")) {line.add(t.getTicketsReservationId());}
            if(fields.contains("Full Name")) {line.add(t.getFullName());}
            if(fields.contains("First Name")) {line.add(t.getFirstName());}
            if(fields.contains("Last Name")) {line.add(t.getLastName());}
            if(fields.contains("E-Mail")) {line.add(t.getEmail());}
            if(fields.contains("Locked")) {line.add(String.valueOf(t.getLockedAssignment()));}
            if(fields.contains("Language")) {line.add(String.valueOf(t.getUserLanguage()));}
            if(fields.contains("Confirmation")) {line.add(reservation.getConfirmationTimestamp().withZoneSameInstant(eventZoneId).toString());}
            if(fields.contains("Billing Address")) {line.add(reservation.getBillingAddress());}
            boolean paymentIdRequested = fields.contains("Payment ID");
            boolean paymentGatewayRequested = fields.contains("Payment Method");
            if((paymentIdRequested || paymentGatewayRequested)) {
                Optional<Transaction> transaction = trs.getTransaction();
                if(paymentIdRequested) { line.add(defaultString(transaction.map(Transaction::getPaymentId).orElse(null), transaction.map(Transaction::getTransactionId).orElse(""))); }
                if(paymentGatewayRequested) { line.add(transaction.map(tr -> tr.getPaymentProxy().name()).orElse("")); }
            }

            if(eInvoicingEnabled) {
                var billingDetails = trs.getBillingDetails();
                var optionalInvoicingData = Optional.ofNullable(billingDetails.getInvoicingAdditionalInfo()).map(TicketReservationInvoicingAdditionalInfo::getItalianEInvoicing);
                if(fields.contains("Fiscal Code")) {line.add(optionalInvoicingData.map(ItalianEInvoicing::getFiscalCode).orElse(""));}
                if(fields.contains("Reference Type")) {line.add(optionalInvoicingData.map(ItalianEInvoicing::getReferenceTypeAsString).orElse(""));}
                if(fields.contains("Addressee Code")) {line.add(optionalInvoicingData.map(ItalianEInvoicing::getAddresseeCode).orElse(""));}
                if(fields.contains("PEC")) {line.add(optionalInvoicingData.map(ItalianEInvoicing::getPec).orElse(""));}
            }

            //obviously not optimized
            Map<String, String> additionalValues = ticketFieldRepository.findAllValuesForTicketId(t.getId());

            Predicate<String> contains = FIXED_FIELDS::contains;

            fields.stream().filter(contains.negate()).filter(f -> f.startsWith(CUSTOM_FIELDS_PREFIX)).forEachOrdered(field -> {
                String customFieldName = field.substring(CUSTOM_FIELDS_PREFIX.length());
                line.add(additionalValues.getOrDefault(customFieldName, "").replaceAll("\"", ""));
            });

            return line.toArray(new String[0]);
        });
    }

    @GetMapping("/events/{eventName}/sponsor-scan/export")
    public void downloadSponsorScanExport(@PathVariable("eventName") String eventName, @RequestParam(name = "format", defaultValue = "excel") String format, HttpServletResponse response, Principal principal) throws IOException {
        var event = eventManager.getSingleEvent(eventName, principal.getName());
        List<TicketFieldConfiguration> fields = ticketFieldRepository.findAdditionalFieldsForEvent(event.getId());

        List<String> header = new ArrayList<>();
        header.add("Username/Api Key");
        header.add("Description");
        header.add("Timestamp");
        header.add("Full name");
        header.add("Email");
        header.addAll(fields.stream().map(TicketFieldConfiguration::getName).collect(toList()));

        Stream<String[]> sponsorScans = userManager.findAllEnabledUsers(principal.getName()).stream()
            .map(u -> Pair.of(u, userManager.getUserRole(u)))
            .filter(p -> p.getRight() == Role.SPONSOR)
            .flatMap(p -> sponsorScanRepository.loadSponsorData(event.getId(), p.getKey().getId(), SponsorScanRepository.DEFAULT_TIMESTAMP)
                .stream()
                .map(v -> Pair.of(v, ticketFieldRepository.findAllValuesForTicketId(v.getTicket().getId()))))
            .map(p -> {
                DetailedScanData data = p.getLeft();
                Map<String, String> descriptions = p.getRight();
                return Pair.of(data, fields.stream().map(x -> descriptions.getOrDefault(x.getName(), "")).collect(toList()));
            }).map(p -> {
            List<String> line = new ArrayList<>();
            Ticket ticket = p.getLeft().getTicket();
            SponsorScan sponsorScan = p.getLeft().getSponsorScan();
            User user = userManager.findUser(sponsorScan.getUserId());
            line.add(user.getUsername());
            line.add(user.getDescription());
            line.add(sponsorScan.getTimestamp().toString());
            line.add(ticket.getFullName());
            line.add(ticket.getEmail());
            line.addAll(p.getRight());
            return line.toArray(new String[0]);
        });

        if ("excel".equals(format)) {
            exportSponsorScanExcel(event.getShortName(), header, sponsorScans, response);
        } else {
            exportSponsorScanCSV(event.getShortName(), header, sponsorScans, response);
        }
    }

    private void exportSponsorScanExcel(String eventName, List<String> header, Stream<String[]> sponsorScans,
                                        HttpServletResponse response) throws IOException {
        ExportUtils.exportExcel(eventName + "-sponsor-scan.xlsx",
            eventName + " sponsor scan",
            header.toArray(new String[0]),
            sponsorScans, response);
    }

    private void exportSponsorScanCSV(String eventName, List<String> header, Stream<String[]> sponsorScans,
                                      HttpServletResponse response) throws IOException {
        ExportUtils.exportCsv(eventName + "-sponsor-scan.csv", header.toArray(new String[0]), sponsorScans, response);
    }

    @GetMapping("/events/{eventName}/fields")
    public List<SerializablePair<String, String>> getAllFields(@PathVariable("eventName") String eventName, Principal principal) {
        var eventAndOrganizationId = eventManager.getEventAndOrganizationId(eventName, principal.getName());
        List<SerializablePair<String, String>> fields = new ArrayList<>(FIXED_PAIRS);
        if(configurationManager.isItalianEInvoicingEnabled(eventAndOrganizationId)) {
            fields.addAll(ITALIAN_E_INVOICING_FIELDS.stream().map(f -> SerializablePair.of(f, f)).collect(toList()));
        }
        fields.addAll(ticketFieldRepository.findFieldsForEvent(eventName).stream().map(f -> SerializablePair.of(CUSTOM_FIELDS_PREFIX + f, f)).collect(toList()));
        return fields;
    }

    @GetMapping("/events/{eventName}/additional-field")
    public List<TicketFieldConfigurationAndAllDescriptions> getAllAdditionalField(@PathVariable("eventName") String eventName) {
        final Map<Integer, List<TicketFieldDescription>> descById = ticketFieldRepository.findDescriptions(eventName).stream().collect(Collectors.groupingBy(TicketFieldDescription::getTicketFieldConfigurationId));
        return ticketFieldRepository.findAdditionalFieldsForEvent(eventName).stream()
            .map(field -> new TicketFieldConfigurationAndAllDescriptions(field, descById.getOrDefault(field.getId(), Collections.emptyList())))
            .collect(toList());
    }

    @GetMapping("/events/{eventName}/additional-field/{id}/stats")
    public List<RestrictedValueStats> getStats(@PathVariable("eventName") String eventName, @PathVariable("id") Integer id, Principal principal) {
        if(eventManager.getOptionalEventAndOrganizationIdByName(eventName, principal.getName()).filter(event -> ticketFieldRepository.findById(id).getEventId() == event.getId()).isEmpty()) {
            return Collections.emptyList();
        }
        return ticketFieldRepository.retrieveStats(id);
    }

    @GetMapping("/event/additional-field/templates")
    public List<DynamicFieldTemplate> loadTemplates() {
        return dynamicFieldTemplateRepository.loadAll();
    }

    @PostMapping("/events/{eventName}/additional-field/descriptions")
    public void saveAdditionalFieldDescriptions(@RequestBody Map<String, TicketFieldDescriptionModification> descriptions) {
        eventManager.updateTicketFieldDescriptions(descriptions);
    }
    
    @PostMapping("/events/{eventName}/additional-field/new")
    public ValidationResult addAdditionalField(@PathVariable("eventName") String eventName, @RequestBody EventModification.AdditionalField field, Principal principal, Errors errors) {
        EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, principal.getName());
        List<TicketFieldConfiguration> fields = ticketFieldRepository.findAdditionalFieldsForEvent(event.getId());
        return validateAdditionalFields(fields, field, errors).ifSuccess(() -> eventManager.addAdditionalField(event, field));
    }
    
    @PostMapping("/events/{eventName}/additional-field/swap-position/{id1}/{id2}")
    public void swapAdditionalFieldPosition(@PathVariable("eventName") String eventName, @PathVariable("id1") int id1, @PathVariable("id2") int id2, Principal principal) {
        EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, principal.getName());
    	eventManager.swapAdditionalFieldPosition(event.getId(), id1, id2);
    }

    @PostMapping("/events/{eventName}/additional-field/set-position/{id}")
    public void setAdditionalFieldPosition(@PathVariable("eventName") String eventName,
                                           @PathVariable("id") int id,
                                           @RequestParam("newPosition") int newPosition,
                                           Principal principal) {
        EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, principal.getName());
        eventManager.setAdditionalFieldPosition(event.getId(), id, newPosition);
    }
    
    @DeleteMapping("/events/{eventName}/additional-field/{id}")
    public void deleteAdditionalField(@PathVariable("eventName") String eventName, @PathVariable("id") int id, Principal principal) {
        eventManager.getEventAndOrganizationId(eventName, principal.getName());
    	eventManager.deleteAdditionalField(id);
    }

    @PostMapping("/events/{eventName}/additional-field/{id}")
    public void updateAdditionalField(@PathVariable("eventName") String eventName, @PathVariable("id") int id, @RequestBody EventModification.UpdateAdditionalField field, Principal principal) {
        eventManager.getEventAndOrganizationId(eventName, principal.getName());
        eventManager.updateAdditionalField(id, field);
    }



    @GetMapping("/events/{eventName}/pending-payments")
    public List<TicketReservationWithTransaction> getPendingPayments(@PathVariable("eventName") String eventName) {
        return ticketReservationManager.getPendingPayments(eventName);
    }

    @GetMapping("/events/{eventName}/pending-payments-count")
    public Integer getPendingPaymentsCount(@PathVariable("eventName") String eventName, Principal principal) {
        return eventManager.getOptionalEventAndOrganizationIdByName(eventName, principal.getName())
            .map(e -> ticketReservationManager.getPendingPaymentsCount(e.getId()))
            .orElse(0);
    }

    @PostMapping("/events/{eventName}/pending-payments/{reservationId}/confirm")
    public String confirmPayment(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, Principal principal) {
        var event = loadEvent(eventName, principal);
        ticketReservationManager.confirmOfflinePayment(event, reservationId, principal.getName());
        ticketReservationManager.findById(reservationId)
            .filter(TicketReservation::isDirectAssignmentRequested)
            .ifPresent(reservation -> {
                Locale locale = LocaleUtil.forLanguageTag(reservation.getUserLanguage(), event);
                ticketHelper.directTicketAssignment(eventName, reservationId, reservation.getEmail(), reservation.getFullName(), reservation.getFirstName(), reservation.getLastName(), reservation.getUserLanguage(), Optional.empty(), locale);
            });
        return OK;
    }

    @DeleteMapping("/events/{eventName}/pending-payments/{reservationId}")
    public String deletePendingPayment(@PathVariable("eventName") String eventName,
                                       @PathVariable("reservationId") String reservationId,
                                       @RequestParam(required = false, value = "credit", defaultValue = "false") Boolean creditReservation,
                                       Principal principal) {
        ticketReservationManager.deleteOfflinePayment(loadEvent(eventName, principal), reservationId, false, Boolean.TRUE.equals(creditReservation), principal.getName());
        return OK;
    }

    @PostMapping("/events/{eventName}/pending-payments/bulk-confirmation")
    public List<Triple<Boolean, String, String>> bulkConfirmation(@PathVariable("eventName") String eventName,
                                                                  Principal principal,
                                                                  @RequestBody UploadBase64FileModification file) throws IOException {

        try(InputStreamReader isr = new InputStreamReader(file.getInputStream()); CSVReader reader = new CSVReader(isr)) {
            Event event = loadEvent(eventName, principal);
            return reader.readAll().stream()
                    .map(line -> {
                        String reservationID = null;
                        try {
                            Validate.isTrue(line.length >= 2);
                            reservationID = line[0];
                            ticketReservationManager.validateAndConfirmOfflinePayment(reservationID, event, new BigDecimal(line[1]), principal.getName());
                            return Triple.of(Boolean.TRUE, reservationID, "");
                        } catch (Exception e) {
                            return Triple.of(Boolean.FALSE, Optional.ofNullable(reservationID).orElse(""), e.getMessage());
                        }
                    })
                    .collect(toList());
        }
    }

    @PutMapping("/events/{eventName}/categories/{categoryId}/tickets/{ticketId}/toggle-locking")
    public boolean toggleTicketLocking(@PathVariable("eventName") String eventName,
                                       @PathVariable("categoryId") int categoryId,
                                       @PathVariable("ticketId") int ticketId,
                                       Principal principal) {
        return eventManager.toggleTicketLocking(eventName, categoryId, ticketId, principal.getName());
    }

    @GetMapping("/events/{eventName}/languages")
    public List<ContentLanguage> getAvailableLocales(@PathVariable("eventName") String eventName) {
        return i18nManager.getEventLanguages(eventName);
    }

    @GetMapping("/events/{eventName}/invoices/count")
    public Integer countInvoicesForEvent(@PathVariable("eventName") String eventName, Principal principal) {
        return eventManager.getOptionalEventAndOrganizationIdByName(eventName, principal.getName())
            .map(e -> ticketReservationManager.countInvoices(e.getId()))
            .orElse(0);
    }

    @GetMapping("/events/{eventName}/all-invoices")
    public void getAllInvoices(@PathVariable("eventName") String eventName, HttpServletResponse response, Principal principal) throws  IOException {
        Event event = loadEvent(eventName, principal);

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=" + event.getShortName() + "-invoices.zip");

        try(OutputStream os = response.getOutputStream(); ZipOutputStream zipOS = new ZipOutputStream(os)) {
            for (Pair<TicketReservation, BillingDocument> pair : ticketReservationManager.findAllInvoices(event.getId())) {
                TicketReservation reservation = pair.getLeft();
                BillingDocument document = pair.getRight();
                Map<String, Object> reservationModel = document.getModel();
                Optional<byte[]> pdf = TemplateProcessor.buildInvoicePdf(event, fileUploadManager, LocaleUtil.forLanguageTag(reservation.getUserLanguage()), templateManager, reservationModel, extensionManager);

                if(pdf.isPresent()) {
                    zipOS.putNextEntry(new ZipEntry("invoice-" + eventName + "-id-" + reservation.getId() + "-invoice-nr-" + document.getNumber() + ".pdf"));
                    StreamUtils.copy(pdf.get(), zipOS);
                }
            }
        }
    }

    @GetMapping("/events-all-languages")
    public List<ContentLanguage> getAllLanguages() {
        return i18nManager.getAvailableLanguages();
    }

    @GetMapping("/events-supported-languages")
    public List<ContentLanguage> getSupportedLanguages() {
        return i18nManager.getSupportedLanguages();
    }

    @GetMapping("/events/{eventName}/category/{categoryId}/ticket")
    public PageAndContent<List<TicketWithStatistic>> getTicketsInCategory(@PathVariable("eventName") String eventName, @PathVariable("categoryId") int categoryId,
                                                                          @RequestParam(value = "page", required = false) Integer page,
                                                                          @RequestParam(value = "search", required = false) String search,
                                                                          Principal principal) {
        EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, principal.getName());
        return new PageAndContent<>(eventStatisticsManager.loadModifiedTickets(event.getId(), categoryId, page == null ? 0 : page, search), eventStatisticsManager.countModifiedTicket(event.getId(), categoryId, search));
    }

    @GetMapping("/events/{eventName}/ticket-sold-statistics")
    public TicketsStatistics getTicketsStatistics(@PathVariable("eventName") String eventName, @RequestParam(value = "from", required = false) String f, @RequestParam(value = "to", required = false) String t, Principal principal) throws ParseException {
        EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, principal.getName());
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        //TODO: cleanup
        Date from = DateUtils.truncate(f == null ? new Date(0) : format.parse(f), Calendar.HOUR);
        Date to = DateUtils.addMilliseconds(DateUtils.ceiling(t == null ? new Date() : format.parse(t), Calendar.DATE), -1);
        //

        int eventId = event.getId();
        return new TicketsStatistics(eventStatisticsManager.getTicketSoldStatistics(eventId, from, to), eventStatisticsManager.getTicketReservedStatistics(eventId, from, to));
    }

    @DeleteMapping("/events/{eventName}/reservation/{reservationId}/transaction/{transactionId}/discard")
    public ResponseEntity<String> discardMatchingPayment(@PathVariable("eventName") String eventName,
                                                       @PathVariable("reservationId") String reservationId,
                                                       @PathVariable("transactionId") int transactionId) {
        var result = ticketReservationManager.discardMatchingPayment(eventName, reservationId, transactionId);
        if(result.isSuccess()) {
            return ResponseEntity.ok("OK");
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    private Event loadEvent(String eventName, Principal principal) {
        Optional<Event> singleEvent = eventManager.getOptionalByName(eventName, principal.getName());
        Validate.isTrue(singleEvent.isPresent(), "event not found");
        return singleEvent.get();
    }

    @Data
    static class TicketsStatistics {
        private final List<TicketsByDateStatistic> sold;
        private final List<TicketsByDateStatistic> reserved;
    }

}
