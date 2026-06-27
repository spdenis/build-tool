package com.example.multibuild.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

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

            // Mirror the proxy config into Java system properties so ProxySelector.getDefault()
            // picks them up. This is the path that enables NTLM transparent authentication:
            // the JVM uses SSPI (Windows) to authenticate with the proxy automatically,
            // which our custom Proxy object above cannot do.
            System.setProperty("http.proxyHost",  proxyHost);
            System.setProperty("http.proxyPort",  String.valueOf(proxyPort));
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));

            // Activate NTLM transparent auth. Only effective on Windows (no-op elsewhere).
            // Allows the JVM to complete the NTLM handshake using the logged-in user's
            // credentials without requiring an Authenticator.
            if (System.getProperty("jdk.http.ntlm.transparentAuth") == null) {
                System.setProperty("jdk.http.ntlm.transparentAuth", "all");
            }

            log.info("HTTP proxy configured: {}:{} (auth: {}, ntlm-transparent: {})",
                    proxyHost, proxyPort,
                    proxyUsername.isBlank() ? "none" : "user=" + proxyUsername,
                    System.getProperty("jdk.http.ntlm.transparentAuth"));
            if (!include.isEmpty()) log.info("  Proxy domains (include): {}", include);
            if (!bypass.isEmpty())  log.info("  Proxy bypass: {}", bypass);

            // Wrap the default system ProxySelector (which reads the system properties set above
            // and carries NTLM support) with our domain include/bypass filtering.
            ProxySelector systemSelector = ProxySelector.getDefault();
            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    String host = uri.getHost();
                    if (host != null) {
                        if (!bypass.isEmpty() && bypass.stream().anyMatch(host::contains)) {
                            log.debug("  Proxy bypass match for {}", host);
                            return List.of(Proxy.NO_PROXY);
                        }
                        if (!include.isEmpty() && include.stream().noneMatch(host::contains)) {
                            log.debug("  No proxy include match for {} — direct", host);
                            return List.of(Proxy.NO_PROXY);
                        }
                    }
                    List<Proxy> proxies = systemSelector.select(uri);
                    log.debug("  Routing {} via {}", host, proxies);
                    return proxies;
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException e) {
                    log.warn("Proxy CONNECT failed for {} via {}: {}", uri, sa, e.getMessage());
                    systemSelector.connectFailed(uri, sa, e);
                }
            });

            // Authenticator for Basic-auth proxies. NTLM uses transparent auth above.
            if (!proxyUsername.isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            log.debug("Providing proxy credentials (Basic) for {}", getRequestingHost());
                            return new PasswordAuthentication(proxyUsername, proxyPassword.toCharArray());
                        }
                        return null;
                    }
                });
            }
        } else {
            log.debug("No HTTP proxy configured (git.proxy.host not set)");
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
