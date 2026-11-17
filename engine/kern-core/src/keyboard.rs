//! Keyboard state: the current page, shift, key geometry, and what each key press does.
//!
//! The keyboard does not touch the text field. A press returns [`Edit`]s, which the platform
//! layer applies to the field. This keeps the engine testable and lets the evaluation harness
//! drive the same code as the phone.

use crate::geometry::{Point, Rect};
use crate::layout::{Key, KeyAction, Layout, Row};

/// Time of an input event in milliseconds, from a monotonic clock.
pub type Timestamp = u64;

/// Two taps on shift within this window lock it.
pub const SHIFT_DOUBLE_TAP_MS: Timestamp = 350;

/// Identifies a key on a specific page, so a press that arrives after the page changed is
/// recognized as stale instead of hitting whatever key now has the same position.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct KeyId {
    pub page: u16,
    pub index: u16,
}

/// What a key looks like and where it is, for drawing and accessibility.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameKey {
    pub id: KeyId,
    pub kind: KeyKind,
    /// Text drawn on the key. Empty for keys drawn with an icon.
    pub label: String,
    pub rect: Rect,
    /// Whether holding the key repeats it.
    pub repeatable: bool,
}

/// Everything needed to draw the keyboard as it is now.
#[derive(Debug, Clone, PartialEq)]
pub struct Frame {
    pub page_id: String,
    pub shift: ShiftIndicator,
    /// The most rows any page of the layout has. The keyboard's height is sized for it, so it
    /// does not jump when the page changes.
    pub row_count: u16,
    pub keys: Vec<FrameKey>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyKind {
    Character,
    Shift,
    Backspace,
    Enter,
    Space,
    SwitchPage,
}

/// How the shift key should look.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShiftIndicator {
    Off,
    /// The next character is uppercase.
    Once,
    /// Caps lock.
    Locked,
}

/// A change to apply to the text field.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Edit {
    /// Insert text at the cursor, replacing the selection if there is one.
    Commit(String),
    /// Delete the selection, or the grapheme before the cursor.
    DeleteBackward,
    /// The enter key: the platform layer decides between a newline and the field's action.
    Enter,
}

/// The result of pressing a key.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Press {
    pub edits: Vec<Edit>,
    /// Whether the frame changed (page, shift, labels) and must be redrawn.
    pub frame_changed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Shift {
    Off,
    /// Turned on by auto-capitalization; turned off again if the field no longer asks for it.
    Auto,
    /// Turned on by the user at `since`; a second tap soon after locks it.
    Once {
        since: Timestamp,
    },
    Locked,
}

/// A keyboard for one layout.
#[derive(Debug, Clone)]
pub struct Keyboard {
    layout: Layout,
    page: usize,
    shift: Shift,
    width: f32,
    height: f32,
    /// Rectangles of the current page's keys, in reading order.
    rects: Vec<Rect>,
}

impl Keyboard {
    pub fn new(layout: Layout) -> Self {
        let mut keyboard = Self {
            layout,
            page: 0,
            shift: Shift::Off,
            width: 0.0,
            height: 0.0,
            rects: Vec::new(),
        };
        keyboard.layout_keys();
        keyboard
    }

    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    /// Sets the size of the key area in pixels.
    pub fn resize(&mut self, width: f32, height: f32) {
        self.width = width.max(0.0);
        self.height = height.max(0.0);
        self.layout_keys();
    }

    /// Returns to the initial state, for a new text field.
    pub fn reset(&mut self) {
        self.page = 0;
        self.shift = Shift::Off;
        self.layout_keys();
    }

    /// Tells the keyboard whether the text field asks for a capital letter at the cursor.
    /// Returns whether the frame changed.
    pub fn set_auto_capitalize(&mut self, active: bool) -> bool {
        let next = match (self.shift, active) {
            (Shift::Off, true) => Shift::Auto,
            (Shift::Auto, false) => Shift::Off,
            (current, _) => current,
        };
        let changed = next != self.shift;
        self.shift = next;
        changed
    }

    pub fn frame(&self) -> Frame {
        let page = &self.layout.pages()[self.page];
        let shifted = self.shift != Shift::Off;
        let keys = page
            .rows()
            .iter()
            .flat_map(Row::keys)
            .zip(&self.rects)
            .enumerate()
            .map(|(index, (key, rect))| {
                let (kind, label) = match key.action() {
                    KeyAction::Character {
                        base,
                        shifted: upper,
                    } => (
                        KeyKind::Character,
                        if shifted { upper.clone() } else { base.clone() },
                    ),
                    KeyAction::Shift => (KeyKind::Shift, String::new()),
                    KeyAction::Backspace => (KeyKind::Backspace, String::new()),
                    KeyAction::Enter => (KeyKind::Enter, String::new()),
                    KeyAction::Space => (KeyKind::Space, String::new()),
                    KeyAction::SwitchPage { label, .. } => (KeyKind::SwitchPage, label.clone()),
                };
                FrameKey {
                    id: self.key_id(index),
                    kind,
                    label,
                    rect: *rect,
                    repeatable: kind == KeyKind::Backspace,
                }
            })
            .collect();
        Frame {
            page_id: page.id().to_owned(),
            shift: self.shift_indicator(),
            row_count: self.layout.max_rows(),
            keys,
        }
    }

    /// The key under `point`. Points between or beside keys go to the nearest key, so the whole
    /// keyboard area is live. `None` only before the keyboard has a size.
    pub fn key_at(&self, point: Point) -> Option<KeyId> {
        if self.width <= 0.0 || self.height <= 0.0 {
            return None;
        }
        if let Some(index) = self.rects.iter().position(|rect| rect.contains(point)) {
            return Some(self.key_id(index));
        }
        self.rects
            .iter()
            .enumerate()
            .min_by(|(_, a), (_, b)| {
                a.distance_squared_to(point)
                    .total_cmp(&b.distance_squared_to(point))
            })
            .map(|(index, _)| self.key_id(index))
    }

    /// Presses a key. A key from a page that is no longer shown does nothing.
    pub fn press(&mut self, id: KeyId, time: Timestamp) -> Press {
        if usize::from(id.page) != self.page {
            return Press::default();
        }
        let Some(action) = self.action(usize::from(id.index)).cloned() else {
            return Press::default();
        };
        match action {
            KeyAction::Character { base, shifted } => {
                let text = if self.shift == Shift::Off {
                    base
                } else {
                    shifted
                };
                let frame_changed = self.consume_one_shot_shift();
                Press {
                    edits: vec![Edit::Commit(text)],
                    frame_changed,
                }
            }
            KeyAction::Space => Press {
                edits: vec![Edit::Commit(" ".to_owned())],
                frame_changed: false,
            },
            KeyAction::Backspace => Press {
                edits: vec![Edit::DeleteBackward],
                frame_changed: false,
            },
            KeyAction::Enter => Press {
                edits: vec![Edit::Enter],
                frame_changed: false,
            },
            KeyAction::Shift => {
                self.shift = match self.shift {
                    Shift::Off | Shift::Auto => Shift::Once { since: time },
                    Shift::Once { since } if time.saturating_sub(since) <= SHIFT_DOUBLE_TAP_MS => {
                        Shift::Locked
                    }
                    Shift::Once { .. } | Shift::Locked => Shift::Off,
                };
                Press {
                    edits: Vec::new(),
                    frame_changed: true,
                }
            }
            KeyAction::SwitchPage { target, .. } => {
                self.page = target;
                self.shift = Shift::Off;
                self.layout_keys();
                Press {
                    edits: Vec::new(),
                    frame_changed: true,
                }
            }
        }
    }

    fn consume_one_shot_shift(&mut self) -> bool {
        match self.shift {
            Shift::Auto | Shift::Once { .. } => {
                self.shift = Shift::Off;
                true
            }
            Shift::Off | Shift::Locked => false,
        }
    }

    fn shift_indicator(&self) -> ShiftIndicator {
        match self.shift {
            Shift::Off => ShiftIndicator::Off,
            Shift::Auto | Shift::Once { .. } => ShiftIndicator::Once,
            Shift::Locked => ShiftIndicator::Locked,
        }
    }

    fn action(&self, index: usize) -> Option<&KeyAction> {
        self.layout.pages()[self.page]
            .rows()
            .iter()
            .flat_map(Row::keys)
            .nth(index)
            .map(Key::action)
    }

    fn key_id(&self, index: usize) -> KeyId {
        // Layout validation caps pages and keys per page at u16::MAX.
        KeyId {
            page: u16::try_from(self.page).expect("page index fits in u16"),
            index: u16::try_from(index).expect("key index fits in u16"),
        }
    }

    /// Computes key rectangles for the current page. Rows share the height equally. The key
    /// unit is the keyboard width over the widest row, and narrower rows are centered.
    fn layout_keys(&mut self) {
        let rows = self.layout.pages()[self.page].rows();
        let widest = rows.iter().map(Row::units).fold(0.0, f32::max);
        #[expect(clippy::cast_precision_loss, reason = "a page has a handful of rows")]
        let row_height = self.height / rows.len() as f32;
        let unit = if widest > 0.0 {
            self.width / widest
        } else {
            0.0
        };

        self.rects.clear();
        let mut top = 0.0;
        for row in rows {
            let mut left = (self.width - row.units() * unit) / 2.0;
            let bottom = top + row_height;
            for key in row.keys() {
                let right = left + key.width() * unit;
                self.rects.push(Rect::new(left, top, right, bottom));
                left = right;
            }
            top = bottom;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builtin;

    fn qwerty(width: f32, height: f32) -> Keyboard {
        let mut keyboard = Keyboard::new(builtin::layout(builtin::QWERTY).unwrap());
        keyboard.resize(width, height);
        keyboard
    }

    fn key(keyboard: &Keyboard, label: &str) -> FrameKey {
        keyboard
            .frame()
            .keys
            .into_iter()
            .find(|key| key.label == label)
            .unwrap_or_else(|| panic!("no key labelled {label:?}"))
    }

    fn key_of_kind(keyboard: &Keyboard, kind: KeyKind) -> FrameKey {
        keyboard
            .frame()
            .keys
            .into_iter()
            .find(|key| key.kind == kind)
            .unwrap_or_else(|| panic!("no {kind:?} key"))
    }

    fn type_label(keyboard: &mut Keyboard, label: &str, time: Timestamp) -> Vec<Edit> {
        let id = key(keyboard, label).id;
        keyboard.press(id, time).edits
    }

    #[test]
    fn rows_fill_the_height_and_keys_do_not_overlap() {
        let keyboard = qwerty(1000.0, 400.0);
        let frame = keyboard.frame();
        let bottom = frame
            .keys
            .iter()
            .map(|key| key.rect.bottom)
            .fold(0.0, f32::max);
        assert!((bottom - 400.0).abs() < 1e-3);
        for (i, a) in frame.keys.iter().enumerate() {
            assert!(a.rect.left >= -1e-3 && a.rect.right <= 1000.0 + 1e-3);
            for b in &frame.keys[i + 1..] {
                let overlap_x =
                    a.rect.left.max(b.rect.left) < a.rect.right.min(b.rect.right) - 1e-3;
                let overlap_y =
                    a.rect.top.max(b.rect.top) < a.rect.bottom.min(b.rect.bottom) - 1e-3;
                assert!(!(overlap_x && overlap_y), "{a:?} overlaps {b:?}");
            }
        }
    }

    #[test]
    fn row_count_is_the_tallest_page() {
        let keyboard = qwerty(1000.0, 400.0);
        let tallest = keyboard
            .layout()
            .pages()
            .iter()
            .map(|page| page.rows().len())
            .max()
            .unwrap();
        assert_eq!(usize::from(keyboard.frame().row_count), tallest);
    }

    #[test]
    fn shorter_rows_are_centered() {
        let keyboard = qwerty(1000.0, 400.0);
        let a = key(&keyboard, "a").rect;
        let l = key(&keyboard, "l").rect;
        assert!((a.left - (1000.0 - l.right)).abs() < 1e-3);
        assert!(a.left > 0.0);
    }

    #[test]
    fn touches_beside_a_row_go_to_the_nearest_key() {
        let keyboard = qwerty(1000.0, 400.0);
        let a = key(&keyboard, "a");
        let beside = Point::new(1.0, f32::midpoint(a.rect.top, a.rect.bottom));
        assert_eq!(keyboard.key_at(beside), Some(a.id));
        let inside = key(&keyboard, "g");
        let center = Point::new(
            f32::midpoint(inside.rect.left, inside.rect.right),
            f32::midpoint(inside.rect.top, inside.rect.bottom),
        );
        assert_eq!(keyboard.key_at(center), Some(inside.id));
    }

    #[test]
    fn no_key_before_the_keyboard_has_a_size() {
        let keyboard = Keyboard::new(builtin::layout(builtin::QWERTY).unwrap());
        assert_eq!(keyboard.key_at(Point::new(0.0, 0.0)), None);
    }

    #[test]
    fn shift_capitalizes_one_character() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let shift = key_of_kind(&keyboard, KeyKind::Shift).id;
        assert!(keyboard.press(shift, 0).frame_changed);
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Once);
        assert_eq!(
            type_label(&mut keyboard, "H", 1000),
            vec![Edit::Commit("H".into())]
        );
        assert_eq!(
            type_label(&mut keyboard, "i", 1100),
            vec![Edit::Commit("i".into())]
        );
    }

    #[test]
    fn double_tap_locks_shift_until_tapped_again() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let shift = key_of_kind(&keyboard, KeyKind::Shift).id;
        keyboard.press(shift, 0);
        keyboard.press(shift, SHIFT_DOUBLE_TAP_MS);
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Locked);
        assert_eq!(
            type_label(&mut keyboard, "O", 1000),
            vec![Edit::Commit("O".into())]
        );
        assert_eq!(
            type_label(&mut keyboard, "K", 1100),
            vec![Edit::Commit("K".into())]
        );
        keyboard.press(shift, 2000);
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Off);
    }

    #[test]
    fn slow_second_tap_turns_shift_off() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let shift = key_of_kind(&keyboard, KeyKind::Shift).id;
        keyboard.press(shift, 0);
        keyboard.press(shift, SHIFT_DOUBLE_TAP_MS + 1);
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Off);
    }

    #[test]
    fn auto_capitalization_follows_the_field() {
        let mut keyboard = qwerty(1000.0, 400.0);
        assert!(keyboard.set_auto_capitalize(true));
        assert_eq!(
            type_label(&mut keyboard, "W", 0),
            vec![Edit::Commit("W".into())]
        );
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Off);

        assert!(keyboard.set_auto_capitalize(true));
        assert!(keyboard.set_auto_capitalize(false));
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Off);
    }

    #[test]
    fn auto_capitalization_does_not_override_manual_shift() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let shift = key_of_kind(&keyboard, KeyKind::Shift).id;
        keyboard.press(shift, 0);
        assert!(!keyboard.set_auto_capitalize(false));
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Once);
    }

    #[test]
    fn page_switch_ignores_presses_from_the_previous_page() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let letter_q = key(&keyboard, "q").id;
        let to_symbols = key(&keyboard, "?123").id;
        keyboard.press(to_symbols, 0);
        assert_eq!(keyboard.frame().page_id, "symbols");
        assert_eq!(keyboard.press(letter_q, 10), Press::default());
        assert_eq!(
            type_label(&mut keyboard, "1", 20),
            vec![Edit::Commit("1".into())]
        );
        let to_letters = key(&keyboard, "ABC").id;
        keyboard.press(to_letters, 30);
        assert_eq!(keyboard.frame().page_id, "letters");
    }

    #[test]
    fn reset_returns_to_the_first_page_without_shift() {
        let mut keyboard = qwerty(1000.0, 400.0);
        let to_symbols = key(&keyboard, "?123").id;
        keyboard.press(to_symbols, 0);
        keyboard.reset();
        assert_eq!(keyboard.frame().page_id, "letters");
        assert_eq!(keyboard.frame().shift, ShiftIndicator::Off);
    }
}
