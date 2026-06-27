package com.example.multibuild.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class HttpClientConfig {

    @Value("${git.proxy.host:}")
    private String proxyHost;

    @Value("${git.proxy.port:8080}")
    private int proxyPort;

    @Value("${git.proxy.username:}")
    private String proxyUsername;

    @Value("${git.proxy.password:}")
    private String proxyPassword;

    // Comma-separated domains that should use the proxy. Empty = all hosts.
    @Value("${git.proxy.domains:}")
    private String proxyDomains;

    // Comma-separated domains that bypass the proxy.
    @Value("${git.proxy.bypass:}")
    private String proxyBypass;

    @Bean
    public HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));

        if (!proxyHost.isBlank()) {
            Set<String> include = parseDomains(proxyDomains);
            Set<String> bypass  = parseDomains(proxyBypass);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    String host = uri.getHost();
                    if (host == null) return List.of(Proxy.NO_PROXY);
                    if (!bypass.isEmpty() && bypass.stream().anyMatch(host::contains)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    if (!include.isEmpty() && include.stream().noneMatch(host::contains)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    return List.of(proxy);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException e) {}
            });

            if (!proxyUsername.isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(proxyUsername, proxyPassword.toCharArray());
                        }
                        return null;
                    }
                });
            }
        }

        return builder.build();
    }

    private static Set<String> parseDomains(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Stream.of(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
