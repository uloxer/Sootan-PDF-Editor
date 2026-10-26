package stirling.software.SPDF.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import stirling.software.common.configuration.RuntimePathConfig;

class ExternalAppDepConfigColdOcrTest {
    private ExternalAppDepConfig config() {
        var endpoints = mock(EndpointConfiguration.class);
        when(endpoints.getEndpointsForGroup(anyString())).thenReturn(Set.of());
        var paths = mock(RuntimePathConfig.class);
        when(paths.getOcrMyPdfPath()).thenReturn("private-ocrmypdf");
        when(paths.getWeasyPrintPath()).thenReturn("weasyprint");
        when(paths.getUnoConvertPath()).thenReturn("unoconvert");
        when(paths.getCalibrePath()).thenReturn("calibre");
        when(paths.getSOfficePath()).thenReturn("soffice");
        return new ExternalAppDepConfig(endpoints, paths);
    }

    private MockedConstruction<ProcessBuilder> probes() {
        return mockConstruction(
                ProcessBuilder.class,
                (builder, context) -> {
                    @SuppressWarnings("unchecked")
                    List<String> command = (List<String>) context.arguments().getFirst();
                    boolean version = command.contains("--version");
                    var process = mock(Process.class);
                    doReturn(process).when(builder).start();
                    doAnswer(call -> !version || (long) call.getArgument(0) >= 6000)
                            .when(process)
                            .waitFor(anyLong(), eq(TimeUnit.MILLISECONDS));
                    doReturn(version ? 0 : 1).when(process).exitValue();
                    doReturn(new ByteArrayInputStream("17.13.0".getBytes()))
                            .when(process)
                            .getInputStream();
                    doReturn(new ByteArrayInputStream(new byte[0])).when(process).getErrorStream();
                });
    }

    @Test
    void sixSecondOcrVersionProbeCompletesWhileOtherProbeBudgetStaysFiveSeconds() throws Exception {
        var config = config();
        Method available =
                ExternalAppDepConfig.class.getDeclaredMethod("isCommandAvailable", String.class);
        available.setAccessible(true);
        try (var probes = probes()) {
            assertEquals(true, available.invoke(config, "private-ocrmypdf"));
            assertEquals(false, available.invoke(config, "other-tool"));
        }
    }

    @Test
    void overallBatchAllowsLookupAndFullColdOcrProbeWithoutEightSecondCancellation()
            throws Exception {
        var config = config();
        ExecutorService pool = mock(ExecutorService.class);
        Field field = ExternalAppDepConfig.class.getDeclaredField("pool");
        field.setAccessible(true);
        field.set(config, pool);
        when(pool.invokeAll(anyList(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(
                        call -> {
                            long budget = call.getArgument(1);
                            assertTrue(
                                    budget > 35000,
                                    "Batch must cover 5s lookup plus 30s OCR and scheduling margin");
                            return List.of();
                        });
        try (var probes = probes()) {
            config.checkDependencies();
        }
        assertTrue(config.isDependenciesChecked());
        verify(pool).invokeAll(anyList(), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(pool).shutdown();
    }
}
