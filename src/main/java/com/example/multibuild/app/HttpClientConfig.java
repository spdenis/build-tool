package com.example.multibuild.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${git.proxy.host:}")
    private String proxyHost;

    @Value("${git.proxy.port:8080}")
    private int proxyPort;

    // Comma-separated domains that bypass the proxy.
    @Value("${git.proxy.bypass:}")
    private String proxyBypass;

    @Bean
    public RestTemplate restTemplate() {
        if (!proxyHost.isBlank()) {
            // Set Java system properties so HttpURLConnection (used by SimpleClientHttpRequestFactory)
            // routes requests through the proxy. This is the same mechanism used by the working test
            // and is the only path that handles NTLM authentication transparently on Windows.
            System.setProperty("http.proxyHost",  proxyHost);
            System.setProperty("http.proxyPort",  String.valueOf(proxyPort));
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));

            if (!proxyBypass.isBlank()) {
                String nonProxyHosts = parseDomains(proxyBypass).stream()
                        .collect(Collectors.joining("|"));
                System.setProperty("http.nonProxyHosts", nonProxyHosts);
                log.info("HTTP proxy: {}:{}, bypass: {}", proxyHost, proxyPort, nonProxyHosts);
            } else {
                log.info("HTTP proxy: {}:{}", proxyHost, proxyPort);
            }
        } else {
            log.debug("No HTTP proxy configured (git.proxy.host not set)");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(60_000);

        RestTemplate restTemplate = new RestTemplate(factory);
        // Never throw on non-2xx — each caller inspects the status code directly.
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }

    private static Set<String> parseDomains(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Stream.of(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
