package com.watertours;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.service.pdfService.PDFCreatorService;
import com.watertours.project.service.pdfService.QRCodeService;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Date;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
@EnableAsync
public class TestHtmxApplication {

	public static void main(String[] args) {
		TicketOrder order = new TicketOrder();
		order.setBuyerName("Иван Иванов");
		order.setTotalAmount(1000);
		order.setStatus(OrderStatus.PAID);
		order.setEmail("123@mail.ru");
		order.setTicketList(List.of(
				new QuickTicket("unique1", TicketType.DISCOUNT, Date.valueOf("2024-08-01"), 1500, UUID.randomUUID()),
				new QuickTicket("unique2", TicketType.CHILD, Date.valueOf("2024-08-01"), 900, UUID.randomUUID()
		)));
		QRCodeService qrService = new QRCodeService("https://water-tours.ru/admin/check-ticket?uniqueId=");
		PDFCreatorService pdfService = new PDFCreatorService(qrService);
		byte[] pdfBytes = pdfService.generateTicketsPdf(order);
		try (FileOutputStream fos = new FileOutputStream("test-tickets.pdf")) {
			fos.write(pdfBytes);
		} catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("PDF успешно сохранён! Открой test-tickets.pdf.");



		Dotenv dotenv = Dotenv.configure().load();
		dotenv.entries().forEach(entry ->System.setProperty(entry.getKey(), entry.getValue()));
		SpringApplication.run(TestHtmxApplication.class, args);


		

	}

}
