package ro.pricechecker.registry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed {@code docs/source-register.md} (AD-8, ADR 0009, S-1). Reads the {@code Domain}, {@code
 * Status} and {@code Limits} columns of the table under {@code ## Retailers}. Only {@code Approved
 * YYYY-MM-DD} with a real date approves; any parse error approves nothing.
 */
public final class SourceRegister {

  private static final String SECTION = "## Retailers";
  private static final Pattern APPROVED = Pattern.compile("Approved (\\d{4}-\\d{2}-\\d{2})");

  /** One register row, cells with backticks stripped. */
  public record Row(String domain, String status, String limits) {}

  private final Map<String, Row> rows;
  private final List<String> errors;

  private SourceRegister(Map<String, Row> rows, List<String> errors) {
    this.rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
    this.errors = List.copyOf(errors);
  }

  public static SourceRegister parse(String markdown) {
    Map<String, Row> rows = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();
    List<String> table = tableLines(markdown == null ? "" : markdown);
    if (table.size() < 2) {
      errors.add("no Retailers table under '" + SECTION + "'");
      return new SourceRegister(rows, errors);
    }
    List<String> header = cells(table.get(0));
    int domainCol = header.indexOf("Domain");
    int statusCol = header.indexOf("Status");
    int limitsCol = header.indexOf("Limits");
    if (domainCol < 0) {
      errors.add("Retailers table has no Domain column");
    }
    if (statusCol < 0) {
      errors.add("Retailers table has no Status column");
    }
    if (!errors.isEmpty()) {
      return new SourceRegister(rows, errors);
    }
    for (int i = 2; i < table.size(); i++) {
      List<String> cells = cells(table.get(i));
      if (cells.size() != header.size()) {
        errors.add(
            "row " + (i - 1) + " has " + cells.size() + " cells, header has " + header.size());
        continue;
      }
      String domain = cells.get(domainCol);
      if (domain.isEmpty()) {
        errors.add("row " + (i - 1) + " has an empty Domain");
        continue;
      }
      String limits = limitsCol < 0 ? "" : cells.get(limitsCol);
      Row previous = rows.putIfAbsent(domain, new Row(domain, cells.get(statusCol), limits));
      if (previous != null) {
        errors.add("duplicate domain " + domain);
      }
    }
    return new SourceRegister(rows, errors);
  }

  /** Lines of the first table under the Retailers heading, up to the end of the table. */
  private static List<String> tableLines(String markdown) {
    List<String> table = new ArrayList<>();
    boolean inSection = false;
    for (String raw : markdown.split("\\R", -1)) {
      String line = raw.strip();
      if (!inSection) {
        inSection = line.equals(SECTION);
        continue;
      }
      if (line.startsWith("## ")) {
        break;
      }
      if (line.startsWith("|")) {
        table.add(line);
      } else if (!table.isEmpty()) {
        break;
      }
    }
    return table;
  }

  private static List<String> cells(String line) {
    String body = line.strip();
    if (body.startsWith("|")) {
      body = body.substring(1);
    }
    if (body.endsWith("|")) {
      body = body.substring(0, body.length() - 1);
    }
    List<String> cells = new ArrayList<>();
    for (String cell : body.split("\\|", -1)) {
      cells.add(cell.replace("`", "").strip());
    }
    return cells;
  }

  /** True only for {@code Approved YYYY-MM-DD} with a real date. */
  static boolean isApprovedStatus(String status) {
    if (status == null) {
      return false;
    }
    Matcher m = APPROVED.matcher(status);
    if (!m.matches()) {
      return false;
    }
    try {
      LocalDate.parse(m.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  public List<String> errors() {
    return errors;
  }

  public Map<String, Row> rows() {
    return rows;
  }

  /** Approved domains; empty when the register has any parse error. */
  public Set<String> approvedDomains() {
    Set<String> approved = new TreeSet<>();
    if (!errors.isEmpty()) {
      return Collections.unmodifiableSet(approved);
    }
    rows.values().stream()
        .filter(r -> isApprovedStatus(r.status()))
        .forEach(r -> approved.add(r.domain()));
    return Collections.unmodifiableSet(approved);
  }

  public boolean isApproved(String domain) {
    return approvedDomains().contains(domain);
  }

  /** Parsed Limits for a domain; empty when the row is missing or the value is unparseable. */
  public Optional<RegisterLimits> limits(String domain) {
    Row row = rows.get(domain);
    return row == null ? Optional.empty() : RegisterLimits.parse(row.limits());
  }
}
