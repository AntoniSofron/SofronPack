package pl.sofron.atmosphere;

import java.util.concurrent.ConcurrentHashMap;

final class AtmosphereMath {
    private AtmosphereMath() {}

    @FunctionalInterface
    private interface Expression {
        double apply(long time, double rain, int skylight);
    }

    // Formulas come from resource packs and normally never change between reloads.
    // Parse once rather than allocating strings and parser state every frame.
    private static final ConcurrentHashMap<String, Expression> COMPILED = new ConcurrentHashMap<>();

    static double evaluate(String input, long dayTime, double rain, int skyLight) {
        Expression expression = COMPILED.get(input);
        if (expression == null) {
            Expression parsed = compile(input);
            if (COMPILED.size() > 512) COMPILED.clear();
            expression = COMPILED.putIfAbsent(input, parsed);
            if (expression == null) expression = parsed;
        }
        double value = expression.apply(dayTime, rain, skyLight);
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Invalid atmospheric expression: " + input);
        return value;
    }

    static void clearCache() {
        COMPILED.clear();
    }

    private static Expression compile(String source) {
        Parser parser = new Parser(source);
        Expression expression = parser.expression();
        parser.spaces();
        if (parser.index != source.length())
            throw new IllegalArgumentException("Unexpected token in: " + source);
        return expression;
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
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

        Expression expression() {
            Expression result = sum();
            while (true) {
                if (accept(">=")) {
                    Expression a=result,b=sum();
                    result=(t,r,s)->a.apply(t,r,s)>=b.apply(t,r,s)?1:0;
                } else if (accept("<=")) {
                    Expression a=result,b=sum();
                    result=(t,r,s)->a.apply(t,r,s)<=b.apply(t,r,s)?1:0;
                } else if (accept("==")) {
                    Expression a=result,b=sum();
                    result=(t,r,s)->a.apply(t,r,s)==b.apply(t,r,s)?1:0;
                } else if (accept(">")) {
                    Expression a=result,b=sum();
                    result=(t,r,s)->a.apply(t,r,s)>b.apply(t,r,s)?1:0;
                } else if (accept("<")) {
                    Expression a=result,b=sum();
                    result=(t,r,s)->a.apply(t,r,s)<b.apply(t,r,s)?1:0;
                } else return result;
            }
        }

        Expression sum() {
            Expression result = product();
            while (true) {
                if (accept("+")) {
                    Expression a=result,b=product();
                    result=(t,r,s)->a.apply(t,r,s)+b.apply(t,r,s);
                } else if (accept("-")) {
                    Expression a=result,b=product();
                    result=(t,r,s)->a.apply(t,r,s)-b.apply(t,r,s);
                } else return result;
            }
        }

        Expression product() {
            Expression result = unary();
            while (true) {
                if (accept("*")) {
                    Expression a=result,b=unary();
                    result=(t,r,s)->a.apply(t,r,s)*b.apply(t,r,s);
                } else if (accept("/")) {
                    Expression a=result,b=unary();
                    result=(t,r,s)->a.apply(t,r,s)/b.apply(t,r,s);
                } else if (accept("%")) {
                    Expression a=result,b=unary();
                    result=(t,r,s)->a.apply(t,r,s)%b.apply(t,r,s);
                } else return result;
            }
        }

        Expression unary() {
            if (accept("-")) {
                Expression arg=unary();
                return (t,r,s)->-arg.apply(t,r,s);
            }
            if (accept("+")) return unary();
            return atom();
        }

        Expression atom() {
            spaces();
            if (accept("(")) {
                Expression value = expression();
                require(")");
                return value;
            }
            if (index >= text.length())
                throw new IllegalArgumentException("Unexpected end: " + text);
            char ch = text.charAt(index);
            if (Character.isDigit(ch) || ch == '.') {
                int start = index;
                while (index < text.length() &&
                        (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) index++;
                double value = Double.parseDouble(text.substring(start, index));
                return (t,r,s) -> value;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = index;
                while (index < text.length() &&
                        (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) index++;
                String word = text.substring(start, index);
                if (accept("(")) {
                    Expression a = expression();
                    require(",");
                    Expression b = expression();
                    require(")");
                    return switch (word) {
                        case "max" -> (t,r,s)->Math.max(a.apply(t,r,s), b.apply(t,r,s));
                        case "min" -> (t,r,s)->Math.min(a.apply(t,r,s), b.apply(t,r,s));
                        default -> throw new IllegalArgumentException("Unsupported function: " + word);
                    };
                }
                return switch (word) {
                    case "DAY_TIME" -> (t,r,s)->t;
                    case "RAIN" -> (t,r,s)->r;
                    case "SKY_LIGHT" -> (t,r,s)->s;
                    default -> throw new IllegalArgumentException("Unsupported variable: " + word);
                };
            }
            throw new IllegalArgumentException("Unexpected character in expression: " + text);
        }
    }
}
