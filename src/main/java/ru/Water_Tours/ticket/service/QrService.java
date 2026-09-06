package ru.Water_Tours.ticket.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.Map;

@Service
public class QrService {

    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE);

    public  BufferedImage generateQRCodeImage(String barcode, int size) throws Exception {
        QRCodeWriter barCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = barCodeWriter.encode(barcode, BarcodeFormat.QR_CODE, size, size);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    /**
     * TRY_HARDER plus a GlobalHistogramBinarizer fallback noticeably improves success on real
     * phone photos of a screen (glare, moire, uneven lighting) compared to a single plain decode.
     */
    public String decodeQRCode(BufferedImage image) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return new MultiFormatReader().decode(bitmap, DECODE_HINTS).getText();
        } catch (NotFoundException e) {
            BinaryBitmap bitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
            return new MultiFormatReader().decode(bitmap, DECODE_HINTS).getText();
        }
    }

}
