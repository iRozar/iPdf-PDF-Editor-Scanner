DROP YOUR FONTS HERE
====================

The text-replacement core (PdfTextEditor) loads a Unicode font from this folder.
The project references:  fonts/NotoSans-Regular.ttf

Download from Google Fonts (https://fonts.google.com) and place the .ttf here:

  NotoSans-Regular.ttf        -> Latin / Cyrillic / Greek
  NotoSansCJK-Regular.ttf     -> Chinese / Japanese / Korean (#21)
  NotoSansArabic-Regular.ttf  -> Arabic (#20, also needs shaping - see README)
  NotoSansHebrew-Regular.ttf  -> Hebrew (#20)

To edit CJK or RTL text, swap the filename in PdfTextEditor.replaceText()
to the matching font (or pick one at runtime based on the script of newText).
Without a font that has the needed glyphs, replaceText() returns false and the
app shows a toast instead of corrupting the page.
