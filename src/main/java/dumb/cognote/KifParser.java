package dumb.cognote;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KifParser {
    private static final int CONTEXT_BUFFER_SIZE = 50;
    private final Reader reader;
    private final StringBuilder contextBuffer = new StringBuilder(CONTEXT_BUFFER_SIZE);
    private int currentChar = -2;
    private int line = 1;
    private int col = 0;
    private int charPos = 0;


    public KifParser(Reader reader) {
        this.reader = reader;
    }

    public static List<Term> parseKif(String input) throws ParseException {
        if (input == null || input.isBlank()) return List.of();
        try (var sr = new StringReader(input.trim())) {
            return new KifParser(sr).parseTopLevel();
        } catch (IOException e) {
            // This should ideally not happen with StringReader, but handle defensively
            throw new ParseException("Internal Read error: " + e.getMessage(), 0, 0, "");
        }
    }

    private static boolean isValidAtomChar(int c) {
        return c != -1 && !Character.isWhitespace(c) && "()\";?".indexOf(c) == -1 && c != ';';
    }

    private List<Term> parseTopLevel() throws IOException, ParseException {
        List<Term> terms = new ArrayList<>();
        consumeWhitespaceAndComments();
        while (peek() != -1) {
            terms.add(parseTerm());
            consumeWhitespaceAndComments();
        }
        return Collections.unmodifiableList(terms);
    }

    private Term parseTerm() throws IOException, ParseException {
        consumeWhitespaceAndComments();
        var next = peek();
        return switch (next) {
            case -1 -> throw createParseException("Unexpected EOF while looking for term", "EOF");
            case '(' -> parseList();
            case '"' -> parseQuotedString();
            case '?' -> parseVariable();
            default -> {
                if (isValidAtomChar(next)) {
                    yield parseAtom();
                } else {
                    throw createParseException("Invalid character at start of term", "'" + (char) next + "'");
                }
            }
        };
    }

    private Term.Lst parseList() throws IOException, ParseException {
        consumeChar('(');
        List<Term> terms = new ArrayList<>();
        while (true) {
            consumeWhitespaceAndComments();
            var next = peek();
            if (next == ')') {
                consumeChar(')');
                return new Term.Lst(terms);
            }
            if (next == -1) throw createParseException("Unmatched parenthesis", "EOF");

            if (next != '(' && next != '"' && next != '?' && !isValidAtomChar(next)) {
                throw createParseException("Invalid character inside list", "'" + (char) next + "'");
            }

            terms.add(parseTerm());
        }
    }

    private Term.Var parseVariable() throws IOException, ParseException {
        consumeChar('?');
        var sb = new StringBuilder("?");
        var next = peek();
        if (!isValidAtomChar(next)) {
            throw createParseException("Variable name character expected after '?'", (next == -1) ? "EOF" : "'" + (char) next + "'");
        }
        while (isValidAtomChar(peek())) {
            sb.append((char) consumeChar());
        }
        if (sb.length() < 2) {
            throw createParseException("Empty variable name after '?'", null);
        }
        return Term.Var.of(sb.toString());
    }

    private Term.Atom parseAtom() throws IOException, ParseException {
        var sb = new StringBuilder();
        var next = peek();
        if (!isValidAtomChar(next)) {
            throw createParseException("Invalid character at start of atom", "'" + (char) next + "'");
        }
        while (isValidAtomChar(peek())) {
            sb.append((char) consumeChar());
        }
        return Term.Atom.of(sb.toString());
    }

    private Term.Atom parseQuotedString() throws IOException, ParseException {
        consumeChar('"');
        var sb = new StringBuilder();
        while (true) {
            var c = consumeChar();
            if (c == '"') return Term.Atom.of(sb.toString());
            if (c == -1) throw createParseException("Unmatched quote in string literal", "EOF");
            if (c == '\\') {
                var next = consumeChar();
                if (next == -1) throw createParseException("EOF after escape character in string literal", "EOF");
                sb.append((char) switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default ->
                            throw createParseException("Invalid escape sequence in string literal", "'\\" + (char) next + "'");
                });
            } else sb.append((char) c);
        }
    }

    private int peek() throws IOException {
        if (currentChar == -2) {
            currentChar = reader.read();
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
            // Add to buffer and trim if necessary
            contextBuffer.append((char) c);
            var cl = contextBuffer.length();
            if (cl > CONTEXT_BUFFER_SIZE) {
                contextBuffer.delete(0, cl - CONTEXT_BUFFER_SIZE);
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

    private void consumeWhitespaceAndComments() throws IOException {
        while (true) {
            var c = peek();
            if (c == -1) break;
            if (Character.isWhitespace(c)) {
                consumeChar();
            } else if (c == ';') {
                do {
                    consumeChar();
                } while (peek() != '\n' && peek() != '\r' && peek() != -1);
            } else {
                break;
            }
        }
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
            this(message, 0, 0, context);
        }

        public ParseException(String message, int line, int col, String context) {
            super(message);
            this.line = line;
            this.col = col;
            this.context = context;
        }

        @Override
        public String getMessage() {
            return super.getMessage() + " at line " + line + " col " + col + ". Context: \"" + context + "\"";
        }
    }
}
