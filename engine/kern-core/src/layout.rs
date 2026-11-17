//! Keyboard layouts.
//!
//! Layouts are data, not code: each one is a JSON file that is parsed and validated into a
//! [`Layout`]. Nothing in the engine assumes a particular layout or language.
//!
//! In a file, a key is either a string, which is a character key with that value, or an object
//! with a `type`:
//!
//! ```json
//! { "id": "example", "pages": [
//!   { "id": "letters", "rows": [
//!     ["q", "w", { "type": "character", "value": "ß", "shifted": "ẞ" }],
//!     [{ "type": "shift", "width": 1.5 }, "z", { "type": "backspace", "width": 1.5 }],
//!     [{ "type": "page", "target": "symbols", "label": "?123" }, { "type": "space", "width": 5 },
//!      { "type": "enter", "width": 1.5 }]
//!   ]},
//!   { "id": "symbols", "rows": [["1", "2", { "type": "page", "target": "letters", "label": "ABC" }]] }
//! ]}
//! ```
//!
//! Widths are in key units (a plain character key is 1). The first page is the initial one.

use serde::Deserialize;

/// Pages per layout and keys per page are capped so they can be addressed with `u16`.
pub const MAX_ITEMS: usize = u16::MAX as usize;

/// A validated keyboard layout.
#[derive(Debug, Clone, PartialEq)]
pub struct Layout {
    id: String,
    pages: Vec<Page>,
    max_rows: u16,
}

impl Layout {
    /// Parses and validates a layout file.
    ///
    /// # Errors
    ///
    /// Returns a [`LayoutError`] when the file is not valid JSON for a layout or breaks one of
    /// the layout rules (unique page ids, known page targets, positive widths, no empty rows).
    pub fn from_json(json: &str) -> Result<Self, LayoutError> {
        let file: LayoutFile = serde_json::from_str(json)?;
        Self::validate(file)
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn pages(&self) -> &[Page] {
        &self.pages
    }

    /// The most rows any page has.
    pub fn max_rows(&self) -> u16 {
        self.max_rows
    }

    fn validate(file: LayoutFile) -> Result<Self, LayoutError> {
        if file.pages.is_empty() {
            return Err(LayoutError::NoPages);
        }
        if file.pages.len() > MAX_ITEMS {
            return Err(LayoutError::TooLarge);
        }
        let page_ids: Vec<&str> = file.pages.iter().map(|page| page.id.as_str()).collect();
        for (index, id) in page_ids.iter().enumerate() {
            if page_ids[..index].contains(id) {
                return Err(LayoutError::DuplicatePage((*id).to_owned()));
            }
        }

        let mut pages = Vec::with_capacity(file.pages.len());
        for page in &file.pages {
            if page.rows.is_empty() {
                return Err(LayoutError::EmptyPage {
                    page: page.id.clone(),
                });
            }
            if page.rows.iter().map(Vec::len).sum::<usize>() > MAX_ITEMS {
                return Err(LayoutError::TooLarge);
            }
            let mut rows = Vec::with_capacity(page.rows.len());
            for (row_index, row) in page.rows.iter().enumerate() {
                if row.is_empty() {
                    return Err(LayoutError::EmptyRow {
                        page: page.id.clone(),
                        row: row_index,
                    });
                }
                let keys = row
                    .iter()
                    .map(|key| Key::from_file(key, &page.id, &page_ids))
                    .collect::<Result<Vec<_>, _>>()?;
                rows.push(Row { keys });
            }
            pages.push(Page {
                id: page.id.clone(),
                rows,
            });
        }

        let max_rows = pages.iter().map(|page| page.rows.len()).max().unwrap_or(0);
        Ok(Self {
            id: file.id,
            pages,
            // Bounded by MAX_ITEMS: every row has at least one key.
            max_rows: u16::try_from(max_rows).map_err(|_| LayoutError::TooLarge)?,
        })
    }
}

/// One page of a layout, such as letters or symbols.
#[derive(Debug, Clone, PartialEq)]
pub struct Page {
    id: String,
    rows: Vec<Row>,
}

impl Page {
    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn rows(&self) -> &[Row] {
        &self.rows
    }
}

/// A row of keys, laid out left to right and centered horizontally.
#[derive(Debug, Clone, PartialEq)]
pub struct Row {
    keys: Vec<Key>,
}

impl Row {
    pub fn keys(&self) -> &[Key] {
        &self.keys
    }

    /// Total width of the row in key units.
    pub fn units(&self) -> f32 {
        self.keys.iter().map(|key| key.width).sum()
    }
}

/// A key and its width in key units.
#[derive(Debug, Clone, PartialEq)]
pub struct Key {
    action: KeyAction,
    width: f32,
}

impl Key {
    pub fn action(&self) -> &KeyAction {
        &self.action
    }

    pub fn width(&self) -> f32 {
        self.width
    }

    fn from_file(key: &KeyFile, page: &str, page_ids: &[&str]) -> Result<Self, LayoutError> {
        let (action, width) = match key {
            KeyFile::Character(value) => (KeyAction::character(value, None, page)?, 1.0),
            KeyFile::Typed(KeyDef::Character {
                value,
                shifted,
                width,
            }) => (
                KeyAction::character(value, shifted.as_deref(), page)?,
                *width,
            ),
            KeyFile::Typed(KeyDef::Shift { width }) => (KeyAction::Shift, *width),
            KeyFile::Typed(KeyDef::Backspace { width }) => (KeyAction::Backspace, *width),
            KeyFile::Typed(KeyDef::Enter { width }) => (KeyAction::Enter, *width),
            KeyFile::Typed(KeyDef::Space { width }) => (KeyAction::Space, *width),
            KeyFile::Typed(KeyDef::Page {
                target,
                label,
                width,
            }) => {
                let target_index =
                    page_ids.iter().position(|id| id == target).ok_or_else(|| {
                        LayoutError::UnknownPage {
                            page: page.to_owned(),
                            target: target.clone(),
                        }
                    })?;
                (
                    KeyAction::SwitchPage {
                        target: target_index,
                        label: label.clone(),
                    },
                    *width,
                )
            }
        };
        if !width.is_finite() || width <= 0.0 {
            return Err(LayoutError::InvalidWidth {
                page: page.to_owned(),
                width,
            });
        }
        Ok(Self { action, width })
    }
}

/// What a key does.
#[derive(Debug, Clone, PartialEq)]
pub enum KeyAction {
    /// Types `base`, or `shifted` while shift is active.
    Character {
        base: String,
        shifted: String,
    },
    Shift,
    Backspace,
    Enter,
    Space,
    /// Switches to the page at index `target` of the layout.
    SwitchPage {
        target: usize,
        label: String,
    },
}

impl KeyAction {
    fn character(value: &str, shifted: Option<&str>, page: &str) -> Result<Self, LayoutError> {
        if value.is_empty() || shifted.is_some_and(str::is_empty) {
            return Err(LayoutError::EmptyCharacter {
                page: page.to_owned(),
            });
        }
        Ok(Self::Character {
            base: value.to_owned(),
            shifted: shifted.map_or_else(|| value.to_uppercase(), str::to_owned),
        })
    }
}

/// Why a layout file was rejected.
#[derive(Debug, thiserror::Error)]
pub enum LayoutError {
    #[error("not a valid layout file: {0}")]
    Parse(#[from] serde_json::Error),
    #[error("the layout has no pages")]
    NoPages,
    #[error("page `{0}` is defined more than once")]
    DuplicatePage(String),
    #[error("page `{page}` has no rows")]
    EmptyPage { page: String },
    #[error("row {row} of page `{page}` has no keys")]
    EmptyRow { page: String, row: usize },
    #[error("a key on page `{page}` has width {width}, which is not a positive number")]
    InvalidWidth { page: String, width: f32 },
    #[error("a character key on page `{page}` has an empty value")]
    EmptyCharacter { page: String },
    #[error("page `{page}` switches to page `{target}`, which does not exist")]
    UnknownPage { page: String, target: String },
    #[error("the layout has more than {MAX_ITEMS} pages, or a page has more than {MAX_ITEMS} keys")]
    TooLarge,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LayoutFile {
    id: String,
    pages: Vec<PageFile>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct PageFile {
    id: String,
    rows: Vec<Vec<KeyFile>>,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum KeyFile {
    Character(String),
    Typed(KeyDef),
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum KeyDef {
    Character {
        value: String,
        shifted: Option<String>,
        #[serde(default = "unit_width")]
        width: f32,
    },
    Shift {
        #[serde(default = "unit_width")]
        width: f32,
    },
    Backspace {
        #[serde(default = "unit_width")]
        width: f32,
    },
    Enter {
        #[serde(default = "unit_width")]
        width: f32,
    },
    Space {
        #[serde(default = "unit_width")]
        width: f32,
    },
    Page {
        target: String,
        label: String,
        #[serde(default = "unit_width")]
        width: f32,
    },
}

const fn unit_width() -> f32 {
    1.0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout(pages: &str) -> Result<Layout, LayoutError> {
        Layout::from_json(&format!(r#"{{ "id": "test", "pages": {pages} }}"#))
    }

    #[test]
    fn string_keys_are_characters_with_uppercase_shift() {
        let layout = layout(r#"[{ "id": "p", "rows": [["q", "ç"]] }]"#).unwrap();
        let keys = layout.pages()[0].rows()[0].keys();
        assert_eq!(
            keys[1].action(),
            &KeyAction::Character {
                base: "ç".into(),
                shifted: "Ç".into()
            }
        );
        assert!((keys[1].width() - 1.0).abs() < f32::EPSILON);
    }

    #[test]
    fn explicit_shifted_value_overrides_uppercase() {
        let layout = layout(
            r#"[{ "id": "p", "rows": [[{ "type": "character", "value": "ß", "shifted": "ẞ" }]] }]"#,
        )
        .unwrap();
        assert_eq!(
            layout.pages()[0].rows()[0].keys()[0].action(),
            &KeyAction::Character {
                base: "ß".into(),
                shifted: "ẞ".into()
            }
        );
    }

    #[test]
    fn page_targets_resolve_to_indices() {
        let layout = layout(
            r#"[{ "id": "a", "rows": [[{ "type": "page", "target": "b", "label": "B" }]] },
                { "id": "b", "rows": [["x"]] }]"#,
        )
        .unwrap();
        assert_eq!(
            layout.pages()[0].rows()[0].keys()[0].action(),
            &KeyAction::SwitchPage {
                target: 1,
                label: "B".into()
            }
        );
    }

    #[test]
    fn rejects_unknown_page_target() {
        let error = layout(
            r#"[{ "id": "a", "rows": [[{ "type": "page", "target": "nope", "label": "?" }]] }]"#,
        )
        .unwrap_err();
        assert!(matches!(error, LayoutError::UnknownPage { target, .. } if target == "nope"));
    }

    #[test]
    fn rejects_duplicate_pages() {
        let error = layout(r#"[{ "id": "a", "rows": [["x"]] }, { "id": "a", "rows": [["y"]] }]"#)
            .unwrap_err();
        assert!(matches!(error, LayoutError::DuplicatePage(id) if id == "a"));
    }

    #[test]
    fn rejects_non_positive_width() {
        let error =
            layout(r#"[{ "id": "a", "rows": [[{ "type": "space", "width": 0 }]] }]"#).unwrap_err();
        assert!(matches!(error, LayoutError::InvalidWidth { .. }));
    }

    #[test]
    fn rejects_empty_rows_and_characters() {
        assert!(matches!(
            layout(r#"[{ "id": "a", "rows": [[]] }]"#).unwrap_err(),
            LayoutError::EmptyRow { row: 0, .. }
        ));
        assert!(matches!(
            layout(r#"[{ "id": "a", "rows": [[""]] }]"#).unwrap_err(),
            LayoutError::EmptyCharacter { .. }
        ));
    }

    #[test]
    fn rejects_unknown_fields() {
        let error =
            layout(r#"[{ "id": "a", "rows": [[{ "type": "space", "wdith": 5 }]] }]"#).unwrap_err();
        assert!(matches!(error, LayoutError::Parse(_)));
    }
}
