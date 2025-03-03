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
package alfio.config;

import alfio.manager.system.ConfigurationManager;
import alfio.model.system.ConfigurationKeys;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Configuration
@ComponentScan(basePackages = {"alfio.controller", "alfio.config"})
@EnableWebMvc
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 4 * 60 * 60) //4h
public class MvcConfiguration implements WebMvcConfigurer {

    private final ConfigurationManager configurationManager;
    private final Environment environment;
    private static final Cache<ConfigurationKeys, String> configurationCache = Caffeine.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .build();

    @Autowired
    public MvcConfiguration(
                            ConfigurationManager configurationManager,
                            Environment environment) {
        this.configurationManager = configurationManager;
        this.environment = environment;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        ResourceHandlerRegistration reg = registry.addResourceHandler("/resources/**").addResourceLocations("/resources/");
        int cacheMinutes = environment.acceptsProfiles(Profiles.of(Initializer.PROFILE_LIVE)) ? 15 : 0;
        reg.setCachePeriod(cacheMinutes * 60);

        //
        registry
            .addResourceHandler("/webjars/**")
            .addResourceLocations("/webjars/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(getCSPInterceptor());
    }

    private HandlerInterceptor getCSPInterceptor() {
        return new HandlerInterceptorAdapter() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                    ModelAndView modelAndView) {

                //
                String reportUri = "";
                boolean enabledReport = Boolean.parseBoolean(configurationCache.get(ConfigurationKeys.SECURITY_CSP_REPORT_ENABLED,
                    k -> configurationManager.getFor(k).getValueOrDefault("false")
                ));
                if (enabledReport) {
                    reportUri = " report-uri " + configurationCache.get(ConfigurationKeys.SECURITY_CSP_REPORT_URI,
                        k -> configurationManager.getFor(k).getValueOrDefault("/report-csp-violation")
                    );
                }
                //


                // http://www.html5rocks.com/en/tutorials/security/content-security-policy/
                // lockdown policy

                response.addHeader("Content-Security-Policy", "default-src 'none'; "//block all by default
                        + " script-src 'self' https://js.stripe.com https://checkout.stripe.com/ https://m.stripe.network https://api.stripe.com/ https://ssl.google-analytics.com/ https://www.google.com/recaptcha/api.js https://www.gstatic.com/recaptcha/api2/ https://maps.googleapis.com/;"//
                        + " style-src 'self' 'unsafe-inline';" // unsafe-inline for style is acceptable...
                        + " img-src 'self' https: data:;"//
                        + " child-src 'self';"
                        + " worker-src 'self';"//webworker
                        + " frame-src 'self' https://js.stripe.com https://checkout.stripe.com https://m.stripe.network https://m.stripe.com https://www.google.com;"
                        + " font-src 'self';"//
                        + " media-src blob: 'self';"//for loading camera api
                        + " connect-src 'self' https://checkout.stripe.com https://m.stripe.network https://m.stripe.com https://maps.googleapis.com/ https://geocoder.cit.api.here.com;" //<- currently stripe.js use jsonp but if they switch to xmlhttprequest+cors we will be ready
                        + reportUri);
            }
        };
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(jacksonMessageConverter());
        StringHttpMessageConverter converter = new StringHttpMessageConverter();
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
        converters.add(converter);
    }

    @Bean
    public MappingJackson2HttpMessageConverter jacksonMessageConverter() {
        final MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper());
        return converter;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    @Bean
    public CommonsMultipartResolver multipartResolver() {
        return new CommonsMultipartResolver();
    }
}
