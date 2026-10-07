using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Tasks;

namespace QuickCaptureMaui;

public class CaptureAttachment
{
    public string SourcePath { get; set; } = "";
    public string StoredPath { get; set; } = "";   // copied into the attachments folder
    public string DisplayName { get; set; } = "";
    public bool IsImage { get; set; }
}

public class NotionClient
{
    private const string ApiBase = "https://api.notion.com/v1";
    private const string NotionVersion = "2022-06-28";
    private const long MaxUploadBytes = 20L * 1024 * 1024; // Notion single-part limit

    private readonly string _token;
    private readonly string _databaseId;

    public NotionClient(string token, string databaseId)
    {
        _token = token;
        _databaseId = databaseId;
    }

    private HttpClient CreateClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        c.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", _token);
        c.DefaultRequestHeaders.Add("Notion-Version", NotionVersion);
        return c;
    }

    public async Task<(bool ok, string message)> CheckConnectionAsync()
    {
        try
        {
            using var c = CreateClient();
            var resp = await c.GetAsync($"{ApiBase}/databases/{_databaseId}");
            if (resp.IsSuccessStatusCode) return (true, "Notion connected");
            return (false, $"Notion error {(int)resp.StatusCode}: {Trim(await resp.Content.ReadAsStringAsync())}");
        }
        catch (Exception ex)
        {
            return (false, "Notion unreachable: " + ex.Message);
        }
    }

    public async Task<(bool ok, string message)> CreateCaptureAsync(
        string text, List<CaptureAttachment> attachments,
        DateTimeOffset? reminder = null, string? topic = null, string? priority = null,
        string? category = null)
    {
        try
        {
            using var c = CreateClient();
            var now = DateTimeOffset.Now;
            var title = string.IsNullOrWhiteSpace(text)
                ? $"Capture {now:yyyy-MM-dd HH:mm}"
                : FirstLine(text, 80);

            var children = new List<object>();
            var skipped = new List<string>();

            // Attachments first, note text after (matches your workflow).
            // Images go before other files (stable order within each group).
            foreach (var a in attachments.OrderByDescending(a => a.IsImage))
            {
                var fi = new FileInfo(a.StoredPath);
                if (!fi.Exists)
                {
                    skipped.Add(a.DisplayName + " (file missing)");
                    continue;
                }
                if (fi.Length > MaxUploadBytes)
                {
                    skipped.Add(a.DisplayName + " (over 20 MB, kept locally only)");
                    continue;
                }
                var uploadId = await UploadFileAsync(c, a.StoredPath, a.DisplayName);
                if (uploadId == null)
                {
                    skipped.Add(a.DisplayName + " (upload failed, kept locally)");
                    continue;
                }
                if (a.IsImage)
                {
                    children.Add(new
                    {
                        @object = "block",
                        type = "image",
                        image = new { type = "file_upload", file_upload = new { id = uploadId } }
                    });
                }
                else
                {
                    children.Add(new
                    {
                        @object = "block",
                        type = "file",
                        file = new { type = "file_upload", file_upload = new { id = uploadId } }
                    });
                }
            }

            foreach (var chunk in Chunk(text, 2000))
            {
                children.Add(new
                {
                    @object = "block",
                    type = "paragraph",
                    paragraph = new
                    {
                        rich_text = new[]
                        {
                            new { type = "text", text = new { content = chunk } }
                        }
                    }
                });
            }

            var props = new Dictionary<string, object?>
            {
                ["Name"] = new { title = new[] { new { text = new { content = title } } } },
                ["Captured"] = new { date = new { start = now.ToString("o") } },
                ["Source"] = new { select = new { name = "Quick Capture" } },
                ["Note"] = new
                {
                    rich_text = new[]
                    {
                        new { type = "text", text = new { content = FirstLine(text, 2000) } }
                    }
                }
            };
            if (reminder.HasValue)
                props["Date"] = new { date = new { start = reminder.Value.ToString("o") } };
            if (!string.IsNullOrWhiteSpace(topic))
                props["Topic"] = new
                {
                    rich_text = new[]
                    {
                        new { type = "text", text = new { content = topic.Length > 2000 ? topic[..2000] : topic } }
                    }
                };
            if (!string.IsNullOrWhiteSpace(priority))
                props["Priority"] = new { select = new { name = priority } };
            if (!string.IsNullOrWhiteSpace(category))
                props["Category"] = new { select = new { name = category } };

            var payload = new Dictionary<string, object?>
            {
                ["parent"] = new { database_id = _databaseId },
                ["properties"] = props,
                ["children"] = children
            };

            var json = JsonSerializer.Serialize(payload,
                new JsonSerializerOptions { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull });
            using var resp = await c.PostAsync($"{ApiBase}/pages",
                new StringContent(json, Encoding.UTF8, "application/json"));
            if (!resp.IsSuccessStatusCode)
                return (false, $"Notion error {(int)resp.StatusCode}: {Trim(await resp.Content.ReadAsStringAsync())}");

            return skipped.Count == 0
                ? (true, "Saved to Notion")
                : (true, "Saved to Notion (" + string.Join("; ", skipped) + ")");
        }
        catch (Exception ex)
        {
            return (false, "Notion failed: " + ex.Message);
        }
    }

    private async Task<string?> UploadFileAsync(HttpClient c, string path, string displayName)
    {
        try
        {
            var contentType = ContentTypeFor(Path.GetExtension(path));
            var createJson = JsonSerializer.Serialize(new
            {
                filename = displayName,
                content_type = contentType,
                mode = "single_part"
            });
            using var createResp = await c.PostAsync($"{ApiBase}/file_uploads",
                new StringContent(createJson, Encoding.UTF8, "application/json"));
            if (!createResp.IsSuccessStatusCode) return null;

            using var doc = JsonDocument.Parse(await createResp.Content.ReadAsStringAsync());
            if (!doc.RootElement.TryGetProperty("id", out var idProp)) return null;
            var id = idProp.GetString();
            if (string.IsNullOrEmpty(id)) return null;

            using var form = new MultipartFormDataContent();
            var bytes = await File.ReadAllBytesAsync(path);
            var byteContent = new ByteArrayContent(bytes);
            byteContent.Headers.ContentType = new MediaTypeHeaderValue(contentType);
            form.Add(byteContent, "file", displayName);

            using var sendResp = await c.PostAsync($"{ApiBase}/file_uploads/{id}/send", form);
            return sendResp.IsSuccessStatusCode ? id : null;
        }
        catch
        {
            return null;
        }
    }

    private static string ContentTypeFor(string ext) => ext.ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".bmp" => "image/bmp",
        ".svg" => "image/svg+xml",
        ".pdf" => "application/pdf",
        ".txt" => "text/plain",
        ".md" => "text/markdown",
        ".zip" => "application/zip",
        ".mp4" => "video/mp4",
        ".mp3" => "audio/mpeg",
        _ => "application/octet-stream"
    };

    private static string FirstLine(string? s, int max)
    {
        var line = (s ?? "").Split('\n')[0].Trim();
        return line.Length <= max ? line : line[..max];
    }

    private static IEnumerable<string> Chunk(string? s, int size)
    {
        s ??= "";
        if (s.Length == 0) yield break;
        for (int i = 0; i < s.Length; i += size)
            yield return s.Substring(i, Math.Min(size, s.Length - i));
    }

    private static string Trim(string s) => s.Length > 180 ? s[..180] + "…" : s;
}
