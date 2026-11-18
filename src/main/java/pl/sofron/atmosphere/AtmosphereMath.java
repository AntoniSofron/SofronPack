package pl.sofron.atmosphere;

final class AtmosphereMath {
    private AtmosphereMath() {}

    static double evaluate(String input, long dayTime, double rain, int skyLight) {
        Parser parser = new Parser(input, dayTime, rain, skyLight);
        double value = parser.expression();
        parser.spaces();
        if (parser.index != input.length() || !Double.isFinite(value))
            throw new IllegalArgumentException("Invalid atmospheric expression: " + input);
        return value;
    }

    private static final class Parser {
        private final String text;
        private final long dayTime;
        private final double rain;
        private final int skyLight;
        private int index;

        Parser(String text, long dayTime, double rain, int skyLight) {
            this.text = text;
            this.dayTime = dayTime;
            this.rain = rain;
            this.skyLight = skyLight;
        }

        void spaces() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        boolean accept(String symbol) {
            spaces();
            if (text.startsWith(symbol, index)) {
                index += symbol.length();
                return true;
            }
            return false;
        }

        void require(String symbol) {
            if (!accept(symbol)) throw new IllegalArgumentException("Expected " + symbol + " in " + text);
        }

        double expression() {
            double result = sum();
            while (true) {
                if (accept(">=")) result = result >= sum() ? 1 : 0;
                else if (accept("<=")) result = result <= sum() ? 1 : 0;
                else if (accept("==")) result = result == sum() ? 1 : 0;
                else if (accept(">")) result = result > sum() ? 1 : 0;
                else if (accept("<")) result = result < sum() ? 1 : 0;
                else return result;
            }
        }

        double sum() {
            double value = product();
            while (true) {
                if (accept("+")) value += product();
                else if (accept("-")) value -= product();
                else return value;
            }
        }

        double product() {
            double value = unary();
            while (true) {
                if (accept("*")) value *= unary();
                else if (accept("/")) value /= unary();
                else if (accept("%")) value %= unary();
                else return value;
            }
        }

        double unary() {
            if (accept("-")) return -unary();
            if (accept("+")) return unary();
            return atom();
        }

        double atom() {
            spaces();
            if (accept("(")) {
                double value = expression();
                require(")");
                return value;
            }
            if (index >= text.length()) throw new IllegalArgumentException("Unexpected end: " + text);
            char ch = text.charAt(index);
            if (Character.isDigit(ch) || ch == '.') {
                int start = index;
                while (index < text.length() &&
                        (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.'))
                    index++;
                return Double.parseDouble(text.substring(start, index));
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = index;
                while (index < text.length() &&
                        (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_'))
                    index++;
                String word = text.substring(start, index);
                if (accept("(")) {
                    double first = expression();
                    require(",");
                    double second = expression();
                    require(")");
                    return switch (word) {
                        case "max" -> Math.max(first, second);
                        case "min" -> Math.min(first, second);
                        default -> throw new IllegalArgumentException("Unsupported function: " + word);
                    };
                }
                return switch (word) {
                    case "DAY_TIME" -> dayTime;
                    case "RAIN" -> rain;
                    case "SKY_LIGHT" -> skyLight;
                    default -> throw new IllegalArgumentException("Unsupported variable: " + word);
                };
            }
            throw new IllegalArgumentException("Unexpected character in expression: " + text);
        }
    }
}
