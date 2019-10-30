/*
 *  Copyright (c) 2018 Cerus
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 * Cerus
 *
 */

package de.cerus.lightshotserver;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.*;
import io.undertow.util.Headers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class LightShotServer {

    public static void main(String[] args) {
        String host;
        int port;
        File pageValid;
        File pageInvalid;
        boolean https;

        List<String> argsList = Arrays.asList(args);
        if (argsList.stream().noneMatch(s -> s.startsWith("host="))) {
            System.out.println("Please specify host (e.g. host=cerus-dev.de)");
            return;
        }
        if (argsList.stream().noneMatch(s -> s.startsWith("port="))) {
            System.out.println("Please specify port (e.g. port=80)");
            return;
        }
        if (argsList.stream().noneMatch(s -> s.startsWith("pageValid="))) {
            System.out.println("Please specify the valid page (e.g. pageValid=page.html)");
            return;
        }
        if (argsList.stream().noneMatch(s -> s.startsWith("pageInvalid="))) {
            System.out.println("Please specify the invalid page (e.g. pageInvalid=page.html)");
            return;
        }
        if (argsList.stream().noneMatch(s -> s.startsWith("https="))) {
            System.out.println("Please specify if you want to use https (e.g. https=true)");
            return;
        }

        host = argsList.stream().filter(s -> s.startsWith("host=")).findFirst().orElse("").substring(5);
        port = Integer.parseInt(argsList.stream().filter(s -> s.startsWith("port=")).findFirst().orElse("").substring(5));
        pageValid = new File(argsList.stream().filter(s -> s.startsWith("pageValid=")).findFirst().orElse("").substring(10));
        pageInvalid = new File(argsList.stream().filter(s -> s.startsWith("pageInvalid=")).findFirst().orElse("").substring(12));
        https = Boolean.parseBoolean(argsList.stream().filter(s -> s.startsWith("https=")).findFirst().orElse("").substring(6));

        String pageValidContent;
        String pageInvalidContent;
        try {
            pageValidContent = String.join("\n", Files.readAllLines(pageValid.toPath()));
            pageInvalidContent = String.join("\n", Files.readAllLines(pageInvalid.toPath()));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        System.out.println("Binding to " + host + ":" + port);

        HttpHandler multipartProcessorHandler = (exchange) -> {
            String requestPath = exchange.getRequestPath();
            System.out.println("Request: " + requestPath);

            if (requestPath.equals("/logo")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/png");
                exchange.getResponseSender().send(ByteBuffer.wrap(Files.readAllBytes(new File(".logo.png").toPath())));
                return;
            }
            if (requestPath.startsWith("/file/")) {
                requestPath = requestPath.substring(6);
                File file = new File(requestPath.replace("/", "").replace("\\", "") + ".png");
                if (file.exists()) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/png");
                    exchange.getResponseSender().send(ByteBuffer.wrap(Files.readAllBytes(file.toPath())));
                } else {
                    exchange.setStatusCode(404);
                }
                return;
            }
            if (!requestPath.equals("/") && !requestPath.startsWith("/upload/")) {
//                requestPath = requestPath.substring(1, requestPath.endsWith("/") ? requestPath.length() - 1 : requestPath.length());
                File file = new File(requestPath.replace("/", "").replace("\\", "") + ".png");
                requestPath = requestPath.substring(1);
                if (file.exists()) {
                    exchange.getResponseSender().send(pageValidContent.replace("${PATH}", requestPath));
                } else {
                    exchange.getResponseSender().send(pageInvalidContent);
                }
                return;
            }

            FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
            if (attachment == null) {
                System.out.println("No attachment found");
                exchange.getResponseSender().send(pageInvalidContent);
                return;
            }
            if (!attachment.contains("image")) {
                System.out.println("Form data does not have image");
                exchange.getResponseSender().send(pageInvalidContent);
                return;
            }

            String path = UUID.randomUUID().toString().replace("-", "");
            System.out.println("Path: " + path);

            FormData.FormValue fileValue = attachment.get("image").getFirst();
            Path file = fileValue.getPath();

            BufferedImage read = ImageIO.read(file.toFile());
            ImageIO.write(read, "png", new File(path + ".png"));

            String url = "http" + (https ? "s" : "") + "://" + host + "/" + path;
            System.out.println(url);
            exchange.getResponseSender().send(String.format("<response>\n<status>success</status>\n<url>%s" +
                    "</url>\n<thumb>%s</thumb>\n</response>", url, url));
        };

        Undertow undertow = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(new EagerFormParsingHandler(
                        FormParserFactory.builder()
                                .addParsers(new MultiPartParserDefinition())
                                .build()
                ).setNext(multipartProcessorHandler))
                .build();
        undertow.start();
    }

}
