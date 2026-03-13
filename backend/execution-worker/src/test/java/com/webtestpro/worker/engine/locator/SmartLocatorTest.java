package com.webtestpro.worker.engine.locator;

import com.webtestpro.worker.entity.TcLocator;
import com.webtestpro.worker.mapper.TcLocatorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SmartLocator – multi-strategy fallback with stability scoring")
@ExtendWith(MockitoExtension.class)
class SmartLocatorTest {

    @Mock private TcLocatorMapper locatorMapper;
    @Mock private WebDriver driver;
    @Mock private WebElement element;

    private SmartLocator locator;
    private static final long EXEC_ID = 999L;

    @BeforeEach
    void setUp() {
        locator = new SmartLocator(locatorMapper);
    }

    private TcLocator makeLocator(String strategy, String value, int score) {
        TcLocator l = new TcLocator();
        l.setStrategy(strategy);
        l.setValue(value);
        l.setStabilityScore(score);
        return l;
    }

    @Test
    @DisplayName("DATA_TESTID strategy (score=5) is tried first")
    void dataTestIdTriedFirst() {
        TcLocator dt = makeLocator("DATA_TESTID", "submit-btn", 5);
        TcLocator css = makeLocator("CSS", ".btn-submit", 3);
        // Mapper returns in arbitrary order — SmartLocator must sort by score DESC
        when(locatorMapper.selectByStepIdOrdered(999L)).thenReturn(new ArrayList<>(List.of(css, dt)));
        when(driver.findElement(any(By.class))).thenReturn(element);

        SmartLocator.LocatorResult result = locator.locate(driver, 999L, EXEC_ID);

        assertThat(result.element()).isSameAs(element);
        // Verify DATA_TESTID was tried (via attribute CSS [data-testid='submit-btn'])
        verify(driver).findElement(By.cssSelector("[data-testid='submit-btn']"));
    }

    @Test
    @DisplayName("falls through to CSS when DATA_TESTID fails")
    void fallsThroughToNextStrategy() {
        TcLocator dt = makeLocator("DATA_TESTID", "missing", 5);
        TcLocator css = makeLocator("CSS", "#fallback", 3);
        when(locatorMapper.selectByStepIdOrdered(999L)).thenReturn(new ArrayList<>(List.of(dt, css)));

        // DATA_TESTID throws, CSS returns element
        when(driver.findElement(By.cssSelector("[data-testid='missing']")))
                .thenThrow(new NoSuchElementException("not found"));
        when(driver.findElement(By.cssSelector("#fallback"))).thenReturn(element);

        SmartLocator.LocatorResult result = locator.locate(driver, 999L, EXEC_ID);
        assertThat(result.element()).isSameAs(element);
    }

    @Test
    @DisplayName("throws NoSuchElementException when all strategies fail")
    void throwsWhenAllFail() {
        TcLocator css = makeLocator("CSS", ".gone", 3);
        when(locatorMapper.selectByStepIdOrdered(999L)).thenReturn(new ArrayList<>(List.of(css)));
        when(driver.findElement(any())).thenThrow(new NoSuchElementException("gone"));

        assertThatThrownBy(() -> locator.locate(driver, 999L, EXEC_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("throws when no locators are registered for step")
    void throwsWhenNoLocatorsRegistered() {
        when(locatorMapper.selectByStepIdOrdered(999L)).thenReturn(List.of());

        assertThatThrownBy(() -> locator.locate(driver, 999L, EXEC_ID))
                .isInstanceOf(NoSuchElementException.class);
    }
}
