## Task: Word / Excel / PowerPoint readers
- NumFmt: Excel number formats (decimals, thousands, percent, scientific, fractions, currency, dates, times, elapsed time, multi-section).
- XlsxStyles: fonts, fills, alignment, wrap, indent per cell format. DocHtml.xlsx: merged cells, hidden sheets/rows/columns, column widths, date1904, numbers right-aligned.
- DocHtml.pptx: slide order from presentation.xml, reading order by position, bullets and numbering, run formatting, hyperlinks, tables with merges, charts as data tables, speaker notes, hidden-slide tag, slide navigation chips.
- DocxStyles + DocHtml.docx: style inheritance, character styles, caps, run/paragraph shading, indent and spacing, outline headings, style-based lists, text boxes (Fallback copy skipped), comments, headers/footers, page breaks.
- Labels come from strings_office.xml. No external libraries were added (pure platform code).
- Build not run locally; converters were tested on the JVM with sample files. Verify on GitHub Actions.
