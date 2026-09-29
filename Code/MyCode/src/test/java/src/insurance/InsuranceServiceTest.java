package src.insurance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class InsuranceServiceTest {

    @Mock
    InsuranceService.UnderwritingPolicy policy;

    @Mock
    InsuranceService.ClaimHistory history;

    @Mock
    InsuranceService.QuoteRepository quotes;


    @Test
    void allParamIsFine() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(true);
        InsuranceService service =
                new InsuranceService(policy, history, quotes);
        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        true,
                        true
                );
        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(10800, result.total());
    }


    @Test
    void ageIsEquals17() {
        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        17,
                        1000000,
                        false,
                        true
                );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.process(request)
        );
        Mockito.verify(policy, Mockito.never()).isAllowed();
    }

    @Test
    void DiscountEquals18_000() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(true);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        1000000,
                        false,
                        false
                );
        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(18000, result.total());
    }


    @Test
    void AvailabilityIsChecked() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        false,
                        false
                );

        service.process(request);

        Mockito.verify(policy, Mockito.times(1)).isAllowed();
    }


    @Test
    void premiumCannotBeLessThan3000() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(3000, result.total());
    }


    @Test
    void ageIsEquals74() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        74,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(10000, result.total());
    }


    @Test
    void InsuranceIsUnavailable() {

        Mockito.when(policy.isAllowed()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(
                "Страхування недоступне",
                result.status()
        );

        Assertions.assertEquals(0, result.total());

        Mockito.verify(quotes, Mockito.never())
                .save(Mockito.any(), Mockito.any());
    }


    @Test
    void ageIsEquals75() {

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        75,
                        100000,
                        false,
                        false
                );

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.process(request)
        );
    }


    @Test
    void quoteIsSaved() {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Mockito.verify(quotes).save(request, result);
    }


    @ParameterizedTest
    @CsvSource({
            "false, false, 3000",
            "true,  false, 5400",
            "false, true,  7200",
            "true,  true,  10800"
    })
    void riskCalculation(
            boolean smoker,
            boolean hazardousActivity,
            long expectedPremium) {

        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(true);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        100000,
                        smoker,
                        hazardousActivity
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(
                expectedPremium,
                result.total()
        );
    }
    @Test
    void requestIsNull() {
        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.process(null)
        );

        Mockito.verify(policy, Mockito.never()).isAllowed();
    }
    @Test
    void ageIsEquals25() {
        // рівно на межі "молодого" тарифу — age <= 25 має бути true
        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        25,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(7000, result.total());
    }

    @Test
    void ageIsEquals65() {
        // рівно на межі "похилого" тарифу — age >= 65 має бути true
        Mockito.when(policy.isAllowed()).thenReturn(true);
        Mockito.when(history.hasNoClaimsDiscount()).thenReturn(false);

        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        65,
                        100000,
                        false,
                        false
                );

        InsuranceService.Result result =
                service.process(request);

        Assertions.assertEquals(10000, result.total());
    }
    @Test
    void insuredAmountTooLow() {
        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        99_999,
                        false,
                        false
                );

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.process(request)
        );

        Mockito.verify(policy, Mockito.never()).isAllowed();
    }

    @Test
    void insuredAmountTooHigh() {
        InsuranceService service =
                new InsuranceService(policy, history, quotes);

        InsuranceService.Request request =
                new InsuranceService.Request(
                        30,
                        10_000_001,
                        false,
                        false
                );

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.process(request)
        );

        Mockito.verify(policy, Mockito.never()).isAllowed();
    }
}
