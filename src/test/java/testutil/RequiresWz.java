package testutil;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import provider.wz.WZFiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A test (or every test in a class) that reads the game's WZ data. {@code wz/} is gitignored, so it is absent on
 * the CI runner: there these tests are reported as skipped, with the reason, instead of failing. Where the data
 * is present (any {@link WZFiles} folder in the server's own {@link WZFiles#DIRECTORY}) they run as before.
 *
 * <p>Mark only tests that pass with the data and fail without it, never a test that fails either way: a skip
 * must not hide a real failure.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("wz")
@ExtendWith(RequiresWz.Present.class)
public @interface RequiresWz {

    final class Present implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Path dir = Path.of(WZFiles.DIRECTORY);
            // Data, not just the folder: an empty wz/ (a stray mount point) holds nothing to read.
            boolean present = Arrays.stream(WZFiles.values()).anyMatch(f -> Files.isDirectory(f.getFile()));
            return present
                    ? ConditionEvaluationResult.enabled("WZ data at " + dir.toAbsolutePath())
                    : ConditionEvaluationResult.disabled("needs WZ data at " + dir.toAbsolutePath()
                            + " (gitignored, so absent in CI)");
        }
    }
}
