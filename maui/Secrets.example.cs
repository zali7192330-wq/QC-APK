namespace QuickCaptureMaui;

// Pre-filled Notion config for the MAUI Android build.
// Copy this file to Secrets.cs and fill in your values.
// Secrets.cs is git-ignored — never commit your token.
public static class Secrets
{
    public const string NotionToken = "PASTE_YOUR_TOKEN_HERE";
    public const string DatabaseId = "PASTE_YOUR_DATABASE_ID_HERE";
    public static readonly string[] Lists = { "Inbox", "Education", "Career", "Fitness", "Health", "Personal" };
    public static readonly string[] Categories = { "Spark", "TIL", "Prompt" };
}
