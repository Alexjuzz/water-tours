package ru.Water_Tours.ticket.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

@Service
public class QrService {
    public  BufferedImage generateQRCodeImage(String barcode, int size) throws Exception {
        QRCodeWriter barCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = barCodeWriter.encode(barcode, BarcodeFormat.QR_CODE, size, size);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

}
