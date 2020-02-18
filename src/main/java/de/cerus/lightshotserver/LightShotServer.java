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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LightShotServer {

    public static void main(String[] args) {
        String host;
        int port;
        File pageValid;
        File pageInvalid;
        boolean https;
        boolean logConnections;

        // Checking if all arguments are set
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

        // Parsing arguments
        host = argsList.stream()
                .filter(s -> s.startsWith("host="))
                .findFirst()
                .orElse("").substring(5);
        port = Integer.parseInt(argsList.stream()
                .filter(s -> s.startsWith("port="))
                .findFirst()
                .orElse("").substring(5));
        pageValid = new File(argsList.stream()
                .filter(s -> s.startsWith("pageValid="))
                .findFirst()
                .orElse("").substring(10));
        pageInvalid = new File(argsList.stream()
                .filter(s -> s.startsWith("pageInvalid="))
                .findFirst()
                .orElse("").substring(12));
        https = Boolean.parseBoolean(argsList.stream()
                .filter(s -> s.startsWith("https="))
                .findFirst()
                .orElse("").substring(6));
        logConnections = Boolean.parseBoolean(argsList.stream()
                .filter(s -> s.startsWith("logCons="))
                .findFirst()
                .orElse("logCons=false").substring(8));

        // Initializing logger
        System.setProperty("java.util.logging.SimpleFormatter.format","[%1$tF %1$tT] [%4$-7s] %5$s %n");
        Logger logger = Logger.getLogger(LightShotServer.class.getName());
        logger.setLevel(Level.ALL);
        try {
            new File("logs").mkdir();
            FileHandler fileHandler = new FileHandler("logs/log-%u.log", 1000 * 1000 * 20, 100, true);
            logger.addHandler(fileHandler);
        } catch (SecurityException | IOException e1) {
            e1.printStackTrace();
            return;
        }

        // Initializing "valid" and "invalid" page
        String pageValidContent;
        String pageInvalidContent;
        try {
            pageValidContent = String.join("\n", Files.readAllLines(pageValid.toPath()));
            pageInvalidContent = String.join("\n", Files.readAllLines(pageInvalid.toPath()));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        logger.info("Binding to " + host + ":" + port);

        // Building the http handler
        HttpHandler multipartProcessorHandler = (exchange) -> {
            String requestPath = exchange.getRequestPath();

            // Endpoint /logo
            // Sends the website logo (.logo.png)
            if (requestPath.equals("/logo")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/png");
                exchange.getResponseSender().send(ByteBuffer.wrap(Files.readAllBytes(new File(".logo.png").toPath())));
                return;
            }

            // Endpoint /file
            // Checks if the image exists and sends the image if it does.
            if (requestPath.startsWith("/file/")) {
                requestPath = requestPath.substring(6);
                File file = new File(requestPath.replace("/", "").replace("\\", "") + ".png");

                if (file.exists()) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/png");
                    exchange.getResponseSender().send(ByteBuffer.wrap(Files.readAllBytes(file.toPath())));
                    setLastModifiedToNow(file);
                } else {
                    exchange.setStatusCode(404);
                }
                return;
            }

            // Endpoint /
            // Same as /file but with a page wrapped around the image.
            if (!requestPath.equals("/") && !requestPath.startsWith("/upload/")) {
                File file = new File(requestPath.replace("/", "").replace("\\", "") + ".png");
                requestPath = requestPath.substring(1);

                if (file.exists()) {
                    exchange.getResponseSender().send(pageValidContent.replace("${PATH}", requestPath));
                    setLastModifiedToNow(file);
                } else {
                    exchange.getResponseSender().send(pageInvalidContent);
                }
                return;
            }

            if(!requestPath.startsWith("/upload/")) {
                exchange.setStatusCode(404);
                return;
            }

            // Endpoint /upload
            // Handles the image uploading.

            // Parsing the supplied form data
            FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
            if (attachment == null) {
                exchange.getResponseSender().send(pageInvalidContent);
                return;
            }

            // Checking if the form data contains an image
            if (!attachment.contains("image")) {
                exchange.getResponseSender().send(pageInvalidContent);
                return;
            }

            // Parsing the temp file path
            FormData.FormValue fileValue = attachment.get("image").getFirst();
            Path file = fileValue.getPath();

            // Generating a key to store the image, reading the temp image and writing it to the key
            String path = UUID.randomUUID().toString().replace("-", "");
            BufferedImage read = ImageIO.read(file.toFile());
            ImageIO.write(read, "png", new File(path + ".png"));

            // Sending "success" data to the Lightshot client
            String url = "http" + (https ? "s" : "") + "://" + host + "/" + path;
            logger.info(url);
            exchange.getResponseSender().send(String.format("<response>\n<status>success</status>\n<url>%s" +
                    "</url>\n<thumb>%s</thumb>\n</response>", url, url));
        };

        // Building the webserver
        Undertow undertow = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(new EagerFormParsingHandler(
                        FormParserFactory.builder()
                                .addParsers(new MultiPartParserDefinition())
                                .build()
                ).setNext(multipartProcessorHandler))
                .build();
        undertow.start();

        // Starting the file cleaner
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            for(File file : new File(".").listFiles()) {
                if(!file.getName().endsWith(".png")) return;
                if(file.getName().startsWith(".")) return;
                if(System.currentTimeMillis() - file.lastModified() < TimeUnit.DAYS.toMillis(28)) return;
                file.delete();
                logger.info("Deleted unused file '"+file.getName()+"'");
            }
        }, 2, 10, TimeUnit.MINUTES);

        logger.info("Server initialized");
    }

    private static void setLastModifiedToNow(File file) {
        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
