package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class QrServiceTest {

    private final QrService service = new QrService();

    @Test
    void decodeQRCodeReturnsWhatWasEncoded() throws Exception {
        String content = "http://localhost:8080/t/51174718-548c-4a83-ba58-10bd3400e12d";

        BufferedImage image = service.generateQRCodeImage(content, 300);
        String decoded = service.decodeQRCode(image);

        assertThat(decoded).isEqualTo(content);
    }
}
