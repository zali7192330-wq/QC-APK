using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace QuickCaptureMaui;

/// <summary>
/// Result of smart-parsing a capture line: reminder date, #tags, ~list,
/// /priority (or !priority) are extracted; the remaining human text is
/// CleanText. Tokens records where each modifier sits in the ORIGINAL
/// input text, for syntax coloring in the editor.
/// </summary>
public enum TokenKind { Priority, Tag, List, DateTime, Category }

public record TokenSpan(int Start, int Length, TokenKind Kind);

public class ParsedCapture
{
    public string CleanText { get; set; } = "";
    public DateTimeOffset? Reminder { get; set; }
    public List<string> Tags { get; set; } = new();
    public string? List { get; set; }
    public string? Category { get; set; }
    public string? Priority { get; set; }
    public List<TokenSpan> Tokens { get; } = new();

    public string Hint
    {
        get
        {
            var parts = new List<string>();
            if (Reminder.HasValue)
            {
                var r = Reminder.Value;
                var today = DateTime.Today;
                string day = r.Date == today ? "Today"
                    : r.Date == today.AddDays(1) ? "Tomorrow"
                    : r.ToString("ddd, MMM d", System.Globalization.CultureInfo.InvariantCulture);
                parts.Add(r.TimeOfDay == TimeSpan.Zero ? day : $"{day} {r:hh:mm tt}");
            }
            parts.AddRange(Tags.Select(t => "#" + t));
            if (!string.IsNullOrEmpty(List)) parts.Add("~" + List);
            if (!string.IsNullOrEmpty(Category)) parts.Add("!" + Category);
            if (!string.IsNullOrEmpty(Priority)) parts.Add(Priority);
            return string.Join("  ·  ", parts);
        }
    }
}

public static class SmartParse
{
    private static readonly Dictionary<string, string> PriorityMap =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["urgent"] = "P0", ["p0"] = "P0",
            ["high"] = "P1", ["p1"] = "P1",
            ["medium"] = "P2", ["p2"] = "P2",
            ["low"] = "P3", ["p3"] = "P3",
        };

    private static readonly Dictionary<string, DayOfWeek> Weekdays =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["sunday"] = DayOfWeek.Sunday, ["monday"] = DayOfWeek.Monday,
            ["tuesday"] = DayOfWeek.Tuesday, ["wednesday"] = DayOfWeek.Wednesday,
            ["thursday"] = DayOfWeek.Thursday, ["friday"] = DayOfWeek.Friday,
            ["saturday"] = DayOfWeek.Saturday,
        };

    private static readonly Dictionary<string, int> Months =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["jan"] = 1, ["january"] = 1, ["feb"] = 2, ["february"] = 2,
            ["mar"] = 3, ["march"] = 3, ["apr"] = 4, ["april"] = 4,
            ["may"] = 5, ["jun"] = 6, ["june"] = 6, ["jul"] = 7, ["july"] = 7,
            ["aug"] = 8, ["august"] = 8, ["sep"] = 9, ["sept"] = 9, ["september"] = 9,
            ["oct"] = 10, ["october"] = 10, ["nov"] = 11, ["november"] = 11,
            ["dec"] = 12, ["december"] = 12,
        };

    private const string DayNames = "sunday|monday|tuesday|wednesday|thursday|friday|saturday";
    private const string MonthNames = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec";

    public static ParsedCapture Parse(string input, string[] knownLists, string[] knownCategories)
    {
        var r = new ParsedCapture();
        input ??= "";
        string padded = " " + input + " "; // padding so boundary lookarounds work at edges
        var now = DateTimeOffset.Now;
        DateTime? date = null;
        TimeSpan? time = null;
        var consumed = new List<(int s, int e)>(); // ranges in ORIGINAL coordinates

        bool Overlaps(int s, int e)
        {
            foreach (var (cs, ce) in consumed)
                if (s < ce && e > cs) return true;
            return false;
        }

        // Finds matches on the padded text; coordinates are converted back to
        // the original input. handle() returns true when the match is consumed.
        void Eat(string pattern, TokenKind kind, Func<Match, bool> handle)
        {
            foreach (Match m in Regex.Matches(padded, pattern, RegexOptions.IgnoreCase))
            {
                int s = m.Index - 1, e = s + m.Length;
                if (s < 0 || e > input.Length || Overlaps(s, e)) continue;
                if (handle(m))
                {
                    consumed.Add((s, e));
                    r.Tokens.Add(new TokenSpan(s, m.Length, kind));
                }
            }
        }

        // priority: #1 / `1 -> P1 .. #3 / `3 -> P3, #0 / `0 -> P0; also /high etc.
        Eat(@"(?<=\s)[#`]([0-3])(?=\s)", TokenKind.Priority, m =>
        {
            r.Priority = "P" + m.Groups[1].Value;
            return true;
        });
        Eat(@"(?<=\s)/(urgent|high|medium|low|p[0-3])(?=\s)", TokenKind.Priority, m =>
        {
            r.Priority = PriorityMap[m.Groups[1].Value];
            return true;
        });

        // #tags
        Eat(@"(?<=\s)#([A-Za-z0-9_]+)(?=\s)", TokenKind.Tag, m =>
        {
            r.Tags.Add(m.Groups[1].Value);
            return true;
        });

        // ~lists
        Eat(@"(?<=\s)~([A-Za-z0-9_]+)(?=\s)", TokenKind.List, m =>
        {
            var v = m.Groups[1].Value;
            r.List = knownLists.FirstOrDefault(l => l.Equals(v, StringComparison.OrdinalIgnoreCase)) ?? v;
            return true;
        });

        // !categories (only consumed when it matches a known category)
        Eat(@"(?<=\s)!([A-Za-z0-9_]+)(?=\s)", TokenKind.Category, m =>
        {
            var v = m.Groups[1].Value;
            var known = knownCategories.FirstOrDefault(c => c.Equals(v, StringComparison.OrdinalIgnoreCase));
            if (known == null) return false; // leave unknown !words as plain text
            r.Category = known;
            return true;
        });

        // relative days
        Eat(@"(?<=\s)day after tomorrow(?=\s)", TokenKind.DateTime, _ => { date = now.Date.AddDays(2); return true; });
        Eat(@"(?<=\s)(tomorrow|tmr)(?=\s)", TokenKind.DateTime, _ => { date = now.Date.AddDays(1); return true; });
        Eat(@"(?<=\s)tonight(?=\s)", TokenKind.DateTime, _ => { date = now.Date; time ??= new TimeSpan(20, 0, 0); return true; });
        Eat(@"(?<=\s)today(?=\s)", TokenKind.DateTime, _ => { date ??= now.Date; return true; });

        // in N hours/minutes/days/weeks
        Eat(@"(?<=\s)in (\d+) (hours?|hrs?|minutes?|mins?|days?|weeks?)(?=\s)", TokenKind.DateTime, m =>
        {
            int n = int.Parse(m.Groups[1].Value);
            var unit = m.Groups[2].Value.ToLowerInvariant();
            DateTimeOffset dt = unit.StartsWith("hour") || unit.StartsWith("hr") ? now.AddHours(n)
                : unit.StartsWith("min") ? now.AddMinutes(n)
                : unit.StartsWith("week") ? now.AddDays(7 * n)
                : now.AddDays(n);
            date = dt.Date; time = dt.TimeOfDay;
            return true;
        });

        // next <weekday> / <weekday>
        Eat(@"(?<=\s)next (" + DayNames + @")(?=\s)", TokenKind.DateTime, m =>
        {
            date = Upcoming(Weekdays[m.Groups[1].Value], now.Date);
            return true;
        });
        Eat(@"(?<=\s)(" + DayNames + @")(?=\s)", TokenKind.DateTime, m =>
        {
            date = Upcoming(Weekdays[m.Groups[1].Value], now.Date);
            return true;
        });

        // parts of day (only meaningful with a date already set, e.g. "tomorrow morning")
        Eat(@"(?<=\s)(morning|afternoon|evening|noon)(?=\s)", TokenKind.DateTime, m =>
        {
            if (date == null) return false; // keep "good morning team" untouched
            time ??= m.Groups[1].Value.ToLowerInvariant() switch
            {
                "morning" => new TimeSpan(9, 0, 0),
                "afternoon" => new TimeSpan(14, 0, 0),
                "evening" => new TimeSpan(18, 0, 0),
                _ => new TimeSpan(12, 0, 0),
            };
            return true;
        });

        // times: at 5pm / 5:30pm / 17:00
        Eat(@"(?<=\s)(?:at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?=\s)", TokenKind.DateTime, m =>
        {
            int h = int.Parse(m.Groups[1].Value);
            int min = m.Groups[2].Success ? int.Parse(m.Groups[2].Value) : 0;
            var ap = m.Groups[3].Value.ToLowerInvariant();
            if (ap == "pm" && h < 12) h += 12;
            if (ap == "am" && h == 12) h = 0;
            if (h > 23) return false;
            time = new TimeSpan(h, min, 0);
            return true;
        });
        Eat(@"(?<=\s)(?:at )?(\d{1,2}):(\d{2})(?=\s)", TokenKind.DateTime, m =>
        {
            int h = int.Parse(m.Groups[1].Value), min = int.Parse(m.Groups[2].Value);
            if (h > 23 || min > 59) return false;
            time = new TimeSpan(h, min, 0);
            return true;
        });

        // explicit dates: dec 25 / 25 dec / 12/25 / 2026-12-25
        DateTime MkDate(int y, int mo, int d)
        {
            var dt = new DateTime(y, mo, d);
            return dt < now.Date ? dt.AddYears(1) : dt;
        }
        Eat(@"(?<=\s)(" + MonthNames + @")[a-z]* (\d{1,2})(?=\s)", TokenKind.DateTime, m =>
        {
            date = MkDate(now.Year, Months[m.Groups[1].Value], int.Parse(m.Groups[2].Value));
            return true;
        });
        Eat(@"(?<=\s)(\d{1,2}) (" + MonthNames + @")[a-z]*(?=\s)", TokenKind.DateTime, m =>
        {
            date = MkDate(now.Year, Months[m.Groups[2].Value], int.Parse(m.Groups[1].Value));
            return true;
        });
        Eat(@"(?<=\s)(\d{1,2})/(\d{1,2})(?=\s)", TokenKind.DateTime, m =>
        {
            int a = int.Parse(m.Groups[1].Value), b = int.Parse(m.Groups[2].Value);
            if (a < 1 || a > 12 || b < 1 || b > 31) return false;
            date = MkDate(now.Year, a, b);
            return true;
        });
        Eat(@"(?<=\s)(\d{4})-(\d{1,2})-(\d{1,2})(?=\s)", TokenKind.DateTime, m =>
        {
            date = new DateTime(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value), int.Parse(m.Groups[3].Value));
            return true;
        });

        // time without date -> today, or tomorrow if that time already passed
        if (time.HasValue && !date.HasValue)
            date = (now.Date + time.Value) <= now ? now.Date.AddDays(1) : now.Date;

        if (date.HasValue)
            r.Reminder = new DateTimeOffset(date.Value + (time ?? TimeSpan.Zero), now.Offset);

        // CleanText: original input minus consumed ranges
        var sb = new StringBuilder();
        int pos = 0;
        foreach (var (s, e) in consumed.OrderBy(c => c.s))
        {
            if (s > pos) sb.Append(input, pos, s - pos);
            pos = Math.Max(pos, e);
        }
        if (pos < input.Length) sb.Append(input, pos, input.Length - pos);
        r.CleanText = Regex.Replace(sb.ToString().Trim(), @"\s+", " ");
        return r;
    }

    private static DateTime Upcoming(DayOfWeek dow, DateTime from)
    {
        int delta = ((int)dow - (int)from.DayOfWeek + 7) % 7;
        return from.AddDays(delta == 0 ? 7 : delta);
    }
}
