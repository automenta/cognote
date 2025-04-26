package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static dumb.cognote.Log.error;

public class KifParser {
    private static final int CONTEXT_BUFFER_SIZE = 50;
    private final Reader reader;
    private final StringBuilder contextBuffer = new StringBuilder(CONTEXT_BUFFER_SIZE);
    private int currentChar = -2;
    private int line = 1;
    private int col = 0;
    private int charPos = 0;


    private KifParser(Reader reader) {
        this.reader = reader;
    }

    public static List<Term> parseKif(String kif) throws ParseException {
        try (var reader = new StringReader(kif)) {
            var parser = new KifParser(reader);
            var terms = new ArrayList<Term>();
            parser.skipWhitespaceAndComments();
            while (parser.peek() != -1) {
                terms.add(parser.parseTerm());
                parser.skipWhitespaceAndComments();
            }
            return terms;
        } catch (IOException e) {
            throw new ParseException("IO Error: " + e.getMessage());
        }
    }

    private int peek() throws IOException {
        if (currentChar == -2) {
            currentChar = reader.read();
            if (contextBuffer.length() >= CONTEXT_BUFFER_SIZE) {
                contextBuffer.deleteCharAt(0);
            }
            if (currentChar != -1) {
                contextBuffer.append((char) currentChar);
            }
        }
        return currentChar;
    }

    private int consumeChar() throws IOException {
        var c = peek();
        if (c != -1) {
            currentChar = -2;
            charPos++;
            if (c == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return c;
    }

    private void consumeChar(char expected) throws IOException, ParseException {
        var actual = consumeChar();
        if (actual != expected) {
            throw createParseException("Expected '" + expected + "'", ((actual == -1) ? "EOF" : "'" + (char) actual + "'"));
        }
    }

    private void skipWhitespaceAndComments() throws IOException {
        while (true) {
            var c = peek();
            if (c == -1) return;
            if (Character.isWhitespace(c)) {
                consumeChar();
            } else if (c == ';') {
                consumeChar();
                while (peek() != '\n' && peek() != -1) {
                    consumeChar();
                }
            } else {
                return;
            }
        }
    }

    private Term parseTerm() throws IOException, ParseException {
        skipWhitespaceAndComments();
        var c = peek();
        if (c == -1) throw createParseException("Unexpected EOF while parsing term");
        return switch (c) {
            case '(' -> parseList();
            case '"' -> parseStringAtom();
            case '?' -> parseVariable();
            default -> parseSymbolAtom();
        };
    }

    private Term.Lst parseList() throws IOException, ParseException {
        consumeChar('(');
        var terms = new ArrayList<Term>();
        skipWhitespaceAndComments();
        while (peek() != ')') {
            if (peek() == -1) throw createParseException("Unexpected EOF inside list");
            terms.add(parseTerm());
            skipWhitespaceAndComments();
        }
        consumeChar(')');
        return new Term.Lst(terms);
    }

    private Term.Atom parseStringAtom() throws IOException, ParseException {
        consumeChar('"');
        var sb = new StringBuilder();
        while (peek() != '"') {
            if (peek() == -1) throw createParseException("Unexpected EOF inside string literal");
            if (peek() == '\\') {
                consumeChar('\\');
                var escaped = consumeChar();
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> throw createParseException("Invalid escape sequence '\\" + (char) escaped + "'");
                }
            } else {
                sb.append((char) consumeChar());
            }
        }
        consumeChar('"');
        return Term.Atom.of(sb.toString());
    }

    private Term.Var parseVariable() throws IOException, ParseException {
        var sb = new StringBuilder();
        sb.append((char) consumeChar());
        var c = peek();
        if (c == -1 || !Character.isLetterOrDigit(c) && c != '_' && c != '-')
            throw createParseException("Variable name must start with '?' followed by letter, digit, '_' or '-'");
        while (peek() != -1 && !Character.isWhitespace(peek()) && peek() != '(' && peek() != ')' && peek() != '"' && peek() != ';') {
            sb.append((char) consumeChar());
        }
        return Term.Var.of(sb.toString());
    }

    private Term.Atom parseSymbolAtom() throws IOException, ParseException {
        var sb = new StringBuilder();
        var c = peek();
        if (c == -1 || Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';' || c == '?')
            throw createParseException("Unexpected character while parsing symbol atom: '" + (char) c + "'");
        while (peek() != -1 && !Character.isWhitespace(peek()) && peek() != '(' && peek() != ')' && peek() != '"' && peek() != ';') {
            sb.append((char) consumeChar());
        }
        var value = sb.toString();
        if (value.isEmpty()) throw createParseException("Empty symbol atom");
        return Term.Atom.of(value);
    }


    private ParseException createParseException(String message) {
        return new ParseException(message, line, col, contextBuffer.toString());
    }

    private ParseException createParseException(String message, @Nullable String foundToken) {
        var foundInfo = foundToken != null ? " found " + foundToken : "";
        return new ParseException(message + foundInfo, line, col, contextBuffer.toString());
    }

    public static class ParseException extends Exception {
        private final int line;
        private final int col;
        private final String context;

        public ParseException(String message) {
            this(message, "");
        }

        public ParseException(String message, String context) {
            this(message, -1, -1, context);
        }

        public ParseException(String message, int line, int col, String context) {
            super(message);
            this.line = line;
            this.col = col;
            this.context = context;
        }

        @Override
        public String getMessage() {
            var location = (line != -1 && col != -1) ? " at line " + line + ", col " + col : "";
            var contextSnippet = context != null && !context.isEmpty() ? " near '" + context + "'" : "";
            return super.getMessage() + location + contextSnippet;
        }
    }
}
