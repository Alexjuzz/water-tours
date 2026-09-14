package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H-2, defence in depth. The local-checkout profile issues fully valid paid tickets with no
 * provider call; "never enable it on production" was a convention written in a document. This
 * makes the application refuse to start instead.
 */
class LocalCheckoutProfileGuardTest {

    @Test
    void startupFailsWhenTheProfileIsActiveOnARealHost() {
        assertThatThrownBy(() -> new LocalCheckoutProfileGuard("https://water-tours.ru").verifyLocalDeployment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-checkout");
    }

    @Test
    void startupFailsWhenTheBaseUrlIsMissingOrUnparseable() {
        assertThatThrownBy(() -> new LocalCheckoutProfileGuard("").verifyLocalDeployment())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalCheckoutProfileGuard("not a url").verifyLocalDeployment())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localDevelopmentIsUnaffected() {
        assertThatCode(() -> new LocalCheckoutProfileGuard("http://localhost:8080").verifyLocalDeployment())
                .doesNotThrowAnyException();
        assertThatCode(() -> new LocalCheckoutProfileGuard("http://127.0.0.1:8080").verifyLocalDeployment())
                .doesNotThrowAnyException();
    }
}
