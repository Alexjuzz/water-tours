package com.watertours.project.service.pdfService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.watertours.project.service.emailService.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@Service
public class QRCodeService {
    private final  org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EmailService.class);
    @Value("${spring.application.qrcode.url-template}")
    private  String URL_TEMPLATE;

    public byte[] generateQRCode(String barcodeText){
        // Создание QR-кода
        logger.info("Вызов метода generateQRCode: Генерация QR-кода для текста: {}", barcodeText);
        String fullBarcodeText = URL_TEMPLATE + barcodeText;
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = qrCodeWriter.encode(fullBarcodeText, BarcodeFormat.QR_CODE, 350, 350);
            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            logger.info("Вызов метода generateQRCode: QR-код успешно сгенерирован для текста: {}", barcodeText);
            return pngOutputStream.toByteArray();
        } catch (WriterException e) {
            logger.error("Вызов метода generateQRCode:  Ошибка WriteException при генерации QR-кода: {}", e.getMessage());
            throw new RuntimeException(e);

        } catch (IOException e) {
            logger.error("Вызов метода generateQRCode: Ошибка IOException при записи QR-кода в поток: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void saveQRCodeImage(String barcodeText, String filePath){
        byte[] qrCodeImage = generateQRCode(barcodeText);
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(qrCodeImage);
            fos.flush();
            System.out.println("Вызов saveQRCodeImage: QR-код успешно сохранен в файл: " + filePath);
        } catch (IOException e) {
            System.err.println("Вызов saveQRCodeImage: Ошибка при сохранении QR-кода в файл: " + e.getMessage());

        }
    }
}
