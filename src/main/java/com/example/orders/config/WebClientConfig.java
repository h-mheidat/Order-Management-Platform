package com.example.orders.config;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The WebClient used to reach the Product Service.
 *
 * <p>Timeouts are set explicitly. A WebClient with no timeout will wait indefinitely for a peer that
 * accepted the connection and then went quiet - which is not a hypothetical: it is what a hung
 * upstream, a dropped packet on a NAT, or a paused container all look like. Indefinite waits exhaust
 * the connection pool and turn one slow dependency into a dead service.
 */
@Configuration
@EnableConfigurationProperties(ProductServiceProperties.class)
public class WebClientConfig {

    @Bean
    WebClient productWebClient(WebClient.Builder builder, ProductServiceProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout())
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(properties.responseTimeout().toMillis(),
                                TimeUnit.MILLISECONDS)));

        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
