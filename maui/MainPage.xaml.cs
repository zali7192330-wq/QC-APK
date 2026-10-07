using System.Text.RegularExpressions;

namespace QuickCaptureMaui;

public partial class MainPage : ContentPage
{
    private readonly NotionClient _notion = new(Secrets.NotionToken, Secrets.DatabaseId);
    private readonly List<CaptureAttachment> _attachments = new();
    private ParsedCapture? _parsed;
    private bool _saving;

    private static readonly string[] ImageExts = { ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp" };

    public MainPage()
    {
        InitializeComponent();
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
#if ANDROID
        var shared = MainActivity.SharedText;
        if (!string.IsNullOrEmpty(shared))
        {
            MainActivity.SharedText = null;
            InputEditor.Text = shared;
        }
#endif
    }

    private void OnInputChanged(object? sender, TextChangedEventArgs e)
    {
        _parsed = SmartParse.Parse(InputEditor.Text ?? "", Secrets.Lists, Secrets.Categories);
        UpdatePreview();
        UpdateChips();
    }

    private static Color ColorFor(TokenKind kind) => kind switch
    {
        TokenKind.Priority => Color.FromArgb("#E57373"),
        TokenKind.List => Color.FromArgb("#9FA8DA"),
        TokenKind.Tag => Color.FromArgb("#CE93D8"),
        TokenKind.Category => Color.FromArgb("#FFD54F"),
        TokenKind.DateTime => Color.FromArgb("#90CAF9"),
        _ => Color.FromArgb("#F5F5F5"),
    };

    private void UpdatePreview()
    {
        var text = InputEditor.Text ?? "";
        var parsed = _parsed;
        if (parsed == null || parsed.Tokens.Count == 0)
        {
            PreviewFrame.IsVisible = false;
            return;
        }
        var fs = new FormattedString();
        int pos = 0;
        foreach (var t in parsed.Tokens.OrderBy(t => t.Start))
        {
            if (t.Start > pos)
                fs.Spans.Add(new Span { Text = text.Substring(pos, t.Start - pos), TextColor = Color.FromArgb("#F5F5F5") });
            fs.Spans.Add(new Span
            {
                Text = text.Substring(t.Start, Math.Min(t.Length, text.Length - t.Start)),
                TextColor = ColorFor(t.Kind),
                FontAttributes = FontAttributes.Bold,
            });
            pos = t.Start + t.Length;
        }
        if (pos < text.Length)
            fs.Spans.Add(new Span { Text = text.Substring(pos), TextColor = Color.FromArgb("#F5F5F5") });
        PreviewLabel.FormattedText = fs;
        PreviewFrame.IsVisible = true;
    }

    private void UpdateChips()
    {
        var p = _parsed;
        bool any = false;

        PriorityChip.IsVisible = !string.IsNullOrEmpty(p?.Priority);
        if (PriorityChip.IsVisible) { PriorityChipLabel.Text = "⚑ " + p!.Priority; any = true; }

        var topicParts = new List<string>();
        if (!string.IsNullOrEmpty(p?.List)) topicParts.Add(p.List);
        if (p != null) topicParts.AddRange(p.Tags);
        TopicChip.IsVisible = topicParts.Count > 0;
        if (TopicChip.IsVisible) { TopicChipLabel.Text = "~ " + string.Join(", ", topicParts); any = true; }

        CategoryChip.IsVisible = !string.IsNullOrEmpty(p?.Category);
        if (CategoryChip.IsVisible) { CategoryChipLabel.Text = "! " + p!.Category; any = true; }

        DateChip.IsVisible = p?.Reminder.HasValue == true;
        if (DateChip.IsVisible) { DateChipLabel.Text = "📅 " + p!.Reminder!.Value.ToString("ddd, MMM d h:mm tt"); any = true; }

        ChipRow.IsVisible = any;
    }

    private async void OnPickList(object? sender, EventArgs e)
    {
        var choice = await DisplayActionSheet("Topic list", "Cancel", null, Secrets.Lists);
        if (string.IsNullOrEmpty(choice) || choice == "Cancel") return;
        var text = Regex.Replace(InputEditor.Text ?? "", @"~[A-Za-z0-9_]+", "").Trim();
        InputEditor.Text = (text + " ~" + choice).Trim();
    }

    private async void OnPickCategory(object? sender, EventArgs e)
    {
        var choice = await DisplayActionSheet("Category", "Cancel", null, Secrets.Categories);
        if (string.IsNullOrEmpty(choice) || choice == "Cancel") return;
        var text = Regex.Replace(InputEditor.Text ?? "", @"![A-Za-z0-9_]+", "").Trim();
        InputEditor.Text = (text + " !" + choice).Trim();
    }

    private async void OnAttach(object? sender, EventArgs e)
    {
        try
        {
            var files = await FilePicker.Default.PickMultipleAsync();
            if (files == null) return;
            foreach (var f in files)
            {
                var ext = Path.GetExtension(f.FileName).ToLowerInvariant();
                var dest = Path.Combine(FileSystem.CacheDirectory,
                    Guid.NewGuid().ToString("N") + ext);
                await using var src = await f.OpenReadAsync();
                await using var dst = File.Create(dest);
                await src.CopyToAsync(dst);
                _attachments.Add(new CaptureAttachment
                {
                    SourcePath = dest,
                    StoredPath = dest,
                    DisplayName = f.FileName,
                    IsImage = ImageExts.Contains(ext),
                });
            }
            RefreshAttachments();
        }
        catch (Exception ex)
        {
            StatusLabel.Text = "Attach failed: " + ex.Message;
        }
    }

    private void RefreshAttachments()
    {
        AttachmentList.Clear();
        foreach (var a in _attachments.ToList())
        {
            var chip = new Frame
            {
                BackgroundColor = Color.FromArgb("#1E1E1E"),
                BorderColor = Color.FromArgb("#444444"),
                Padding = new Thickness(10, 6),
                Content = new Label
                {
                    Text = (a.IsImage ? "🖼 " : "📄 ") + a.DisplayName,
                    TextColor = Color.FromArgb("#F5F5F5"),
                    FontSize = 13,
                },
            };
            chip.GestureRecognizers.Add(new TapGestureRecognizer
            {
                Command = new Command(() =>
                {
                    _attachments.Remove(a);
                    try { File.Delete(a.StoredPath); } catch { }
                    RefreshAttachments();
                }),
            });
            AttachmentList.Add(chip);
        }
    }

    private async void OnSave(object? sender, EventArgs e)
    {
        if (_saving) return;
        var parsed = SmartParse.Parse(InputEditor.Text ?? "", Secrets.Lists, Secrets.Categories);
        if (string.IsNullOrWhiteSpace(parsed.CleanText) && _attachments.Count == 0)
        {
            StatusLabel.Text = "Type something or attach a file first.";
            return;
        }
        _saving = true;
        SaveButton.IsEnabled = false;
        StatusLabel.Text = "Saving…";
        try
        {
            var topicParts = new List<string>();
            if (!string.IsNullOrEmpty(parsed.List)) topicParts.Add(parsed.List);
            topicParts.AddRange(parsed.Tags);
            var (ok, message) = await _notion.CreateCaptureAsync(
                parsed.CleanText, _attachments.ToList(),
                parsed.Reminder, string.Join(", ", topicParts),
                parsed.Priority, parsed.Category);
            StatusLabel.Text = message;
            StatusLabel.TextColor = ok ? Color.FromArgb("#2DD4BF") : Color.FromArgb("#E57373");
            if (ok)
            {
                InputEditor.Text = "";
                foreach (var a in _attachments) { try { File.Delete(a.StoredPath); } catch { } }
                _attachments.Clear();
                RefreshAttachments();
            }
        }
        catch (Exception ex)
        {
            StatusLabel.Text = "Notion failed: " + ex.Message;
            StatusLabel.TextColor = Color.FromArgb("#E57373");
        }
        finally
        {
            _saving = false;
            SaveButton.IsEnabled = true;
        }
    }
}
