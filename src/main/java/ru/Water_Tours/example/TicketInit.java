package ru.Water_Tours.example;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.time.Instant;
import java.util.UUID;

//@Component
public class TicketInit implements ApplicationRunner {
    private final TicketRepository repository;
    TicketInit(TicketRepository repository){
        this.repository = repository;
    }
    @Override
    public void run(ApplicationArguments args){
        if(repository.count() == 0){
            var t = new Ticket();
            t.setId(UUID.randomUUID()); 
            t.setPurchaseEmail("example@mail.ru");
            t.setCode("example_code");
            t.setPurchaseDate(Instant.now());
            repository.save(t);
        }
    }

}
