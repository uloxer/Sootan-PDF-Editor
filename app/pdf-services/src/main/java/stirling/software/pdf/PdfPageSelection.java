package stirling.software.pdf;

import java.util.*;
import java.util.regex.Pattern;

import com.fathzer.soft.javaluator.DoubleEvaluator;

/** Retained bounded common page-selection grammar for non-web processing. */
public final class PdfPageSelection {
    private PdfPageSelection() {}

    public static List<Integer> parsePageList(String pages, int totalPages, boolean oneBased) {
        if (pages == null) {
            return List.of(1); // Default to first page if input is null
        }
        return parsePageList(pages.split(","), totalPages, oneBased);
    }

    public static List<Integer> parsePageList(String[] pages, int totalPages) {
        return parsePageList(pages, totalPages, false);
    }

    public static List<Integer> parsePageList(String[] pages, int totalPages, boolean oneBased) {
        Set<Integer> result = new LinkedHashSet<>();
        int offset = oneBased ? 1 : 0;
        int maxSize = Math.max(1000, totalPages * 3);
        for (String page : pages) {
            String trimmedPage = page == null ? "" : page.trim();
            if ("all".equalsIgnoreCase(trimmedPage)) {

                for (int i = 0; i < totalPages; i++) {
                    result.add(i + offset);
                }
            } else if (trimmedPage.contains(",")) {
                String[] parts = trimmedPage.split(",");
                for (String part : parts) {
                    result.addAll(handlePart(part, totalPages, offset));
                }
            } else {
                result.addAll(handlePart(trimmedPage, totalPages, offset));
            }
            if (result.size() > maxSize) {
                throw new IllegalArgumentException(
                        "Page list exceeds maximum allowed size of " + maxSize);
            }
        }
        return new ArrayList<>(result);
    }

    public static List<Integer> evaluateNFunc(String expression, int maxValue) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("Expression cannot be null or empty");
        }

        if (maxValue <= 0 || maxValue > 10000) {
            throw new IllegalArgumentException("maxValue must be between 1 and 10000 for safety");
        }

        List<Integer> results = new ArrayList<>();
        DoubleEvaluator evaluator = new DoubleEvaluator();

        if (!isMathExpression(expression)) {
            throw new IllegalArgumentException("Invalid expression format: " + expression);
        }

        for (int n = 1; n <= maxValue; n++) {
            try {
                String sanitizedExpression = sanitizeNFunction(expression.trim(), n);
                Double result = evaluator.evaluate(sanitizedExpression);

                if (result == null || !Double.isFinite(result)) {
                    continue;
                }

                int intResult = result.intValue();
                if (intResult > 0 && intResult <= maxValue) {
                    results.add(intResult);
                }
            } catch (Exception ignored) {
                continue;
            }
        }

        return results;
    }

    private static boolean isMathExpression(String expression) {
        return Pattern.compile("[0-9n+\\-*/() ]+").matcher(expression.trim()).matches();
    }

    private static boolean isNFunction(String expression) {
        if (!isMathExpression(expression)) {
            return false;
        }
        try {
            new DoubleEvaluator().evaluate(sanitizeNFunction(expression.trim(), 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeNFunction(String expression, int nValue) {
        StringBuilder sb = new StringBuilder(expression.length());
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c != ' ') {
                sb.append(c);
            }
        }
        String sanitizedExpression = sb.toString();
        String multiplyByOpeningRoundBracketPattern =
                "([0-9n)])\\("; // example: n(n-1), 9(n-1), (n-1)(n-2)
        sanitizedExpression =
                sanitizedExpression.replaceAll(multiplyByOpeningRoundBracketPattern, "$1*(");

        String multiplyByClosingRoundBracketPattern =
                "\\)([0-9n)])"; // example: (n-1)n, (n-1)9, (n-1)(n-2)
        sanitizedExpression =
                sanitizedExpression.replaceAll(multiplyByClosingRoundBracketPattern, ")*$1");

        sanitizedExpression = insertMultiplicationBeforeN(sanitizedExpression, nValue);
        return sanitizedExpression;
    }

    private static List<Integer> handlePart(String part, int totalPages, int offset) {
        List<Integer> partResult = new ArrayList<>();
        String trimmedPart = part == null ? "" : part.trim();
        if (trimmedPart.isEmpty()) {
            return partResult;
        }

        if (trimmedPart.contains("n") && isNFunction(trimmedPart)) {
            partResult = evaluateNFunc(trimmedPart, totalPages);
            for (int i = 0; i < partResult.size(); i++) {
                int adjustedValue = partResult.get(i) - 1 + offset;
                partResult.set(i, adjustedValue);
            }
        } else if (trimmedPart.contains("-")) {
            String[] rangeParts = trimmedPart.split("-", -1);
            try {
                int start = Integer.parseInt(rangeParts[0].trim());
                int end =
                        (rangeParts.length > 1 && !rangeParts[1].trim().isEmpty())
                                ? Integer.parseInt(rangeParts[1].trim())
                                : totalPages;
                for (int i = Math.max(1, start); i <= Math.min(end, totalPages); i++) {
                    if (i >= 1 && i <= totalPages) {
                        partResult.add(i - 1 + offset);
                    }
                }
            } catch (NumberFormatException ignored) {
                return partResult;
            }
        } else {
            try {
                int pageNum = Integer.parseInt(trimmedPart);
                if (pageNum >= 1 && pageNum <= totalPages) {
                    partResult.add(pageNum - 1 + offset);
                }
            } catch (NumberFormatException ignored) {
                return partResult;
            }
        }
        return partResult;
    }

    private static String insertMultiplicationBeforeN(String expression, int nValue) {
        StringBuilder sb = new StringBuilder(expression.length() + 4); // +4 for possible extra '*'
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            sb.append(c);
            if (Character.isDigit(c)
                    && i + 1 < expression.length()
                    && expression.charAt(i + 1) == 'n') {
                sb.append('*');
            }
        }
        String withMultiplication = sb.toString();
        withMultiplication = formatConsecutiveNsForNFunction(withMultiplication);
        return withMultiplication.replace("n", String.valueOf(nValue));
    }

    private static String formatConsecutiveNsForNFunction(String expression) {
        String text = expression;
        while (text.contains("nn")) {
            StringBuilder sb = new StringBuilder(text.length() + 2); // +2 for possible extra '*'
            int i = 0;
            while (i < text.length()) {
                if (i < text.length() - 1 && text.charAt(i) == 'n' && text.charAt(i + 1) == 'n') {
                    sb.append("n*n");
                    i += 2;
                } else {
                    sb.append(text.charAt(i));
                    i++;
                }
            }
            text = sb.toString();
        }
        return text;
    }
}
