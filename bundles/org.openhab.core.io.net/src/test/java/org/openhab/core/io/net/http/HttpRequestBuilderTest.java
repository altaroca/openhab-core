/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.io.net.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * Test cases for the <code>HttpRequestBuilder</code> to validate its behaviour
 *
 * @author Martin van Wingerden and Wouter Born - Initial contribution
 */
@NonNullByDefault
public class HttpRequestBuilderTest extends BaseHttpUtilTest {

    @Test
    public void baseTest() throws Exception {
        mockResponse(HttpStatus.OK_200);

        String result = HttpRequestBuilder.getFrom(URL).getContentAsString();

        assertEquals("Some content", result);

        verify(httpClientMock).newRequest(URI.create(URL));
        verify(requestMock).method(HttpMethod.GET);
        verify(requestMock).send();
    }

    @Test
    public void testHeader() throws Exception {
        mockResponse(HttpStatus.OK_200);

        // @formatter:off
        String result = HttpRequestBuilder.getFrom(URL)
                .withHeader("Authorization", "Bearer sometoken")
                .withHeader("X-Token", "test")
                .getContentAsString();
        // @formatter:on

        assertEquals("Some content", result);

        // verify the headers to be added to the request
        verify(requestMock).header("Authorization", "Bearer sometoken");
        verify(requestMock).header("X-Token", "test");
    }

    @Test
    public void testTimeout() throws Exception {
        mockResponse(HttpStatus.OK_200);

        String result = HttpRequestBuilder.getFrom(URL).withTimeout(Duration.ofMillis(200)).getContentAsString();

        assertEquals("Some content", result);

        // verify the timeout to be forwarded
        verify(requestMock).timeout(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testPostWithContent() throws Exception {
        ArgumentCaptor<ContentProvider> argumentCaptor = ArgumentCaptor.forClass(ContentProvider.class);

        mockResponse(HttpStatus.OK_200);

        String result = HttpRequestBuilder.postTo(URL).withContent("{json: true}").getContentAsString();

        assertEquals("Some content", result);

        // verify the content to be added to the request
        verify(requestMock).content(argumentCaptor.capture(), ArgumentMatchers.eq(null));

        assertEquals("{json: true}", getContentFromProvider(argumentCaptor.getValue()));
    }

    private String getContentFromProvider(ContentProvider value) {
        ByteBuffer element = value.iterator().next();
        byte[] data = new byte[element.limit()];
        // Explicit cast for compatibility with covariant return type on JDK 9's ByteBuffer
        ((ByteBuffer) ((Buffer) element.duplicate()).clear()).get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    @Test
    public void testPostWithContentType() throws Exception {
        ArgumentCaptor<ContentProvider> argumentCaptor = ArgumentCaptor.forClass(ContentProvider.class);

        mockResponse(HttpStatus.OK_200);

        String result = HttpRequestBuilder.postTo(URL).withContent("{json: true}", "application/json")
                .getContentAsString();

        assertEquals("Some content", result);

        // verify just the content-type to be added to the request
        verify(requestMock).method(HttpMethod.POST);
        verify(requestMock).content(argumentCaptor.capture(), ArgumentMatchers.eq("application/json"));
    }
}
