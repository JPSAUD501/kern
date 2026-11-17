//! The engine's surface for Kotlin, generated with `UniFFI`.
//!
//! This crate only converts between `kern-core` types and FFI types. Behavior belongs in
//! `kern-core`, where it is tested and where the evaluation harness can reach it.

use std::sync::{Mutex, MutexGuard, PoisonError};

use kern_core::{builtin, geometry, keyboard};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("there is no built-in layout `{id}`")]
    UnknownLayout { id: String },
    #[error("layout `{id}` is invalid: {reason}")]
    InvalidLayout { id: String, reason: String },
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct KeyRect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

/// Opaque to Kotlin: received from the engine and passed back to it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct KeyId {
    pub page: u16,
    pub index: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KeyKind {
    Character,
    Shift,
    Backspace,
    Enter,
    Space,
    SwitchPage,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ShiftIndicator {
    Off,
    Once,
    Locked,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FrameKey {
    pub id: KeyId,
    pub kind: KeyKind,
    pub label: String,
    pub rect: KeyRect,
    pub repeatable: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KeyboardFrame {
    pub page_id: String,
    pub shift: ShiftIndicator,
    pub row_count: u16,
    pub keys: Vec<FrameKey>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Edit {
    Commit { text: String },
    DeleteBackward,
    Enter,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct KeyPress {
    pub edits: Vec<Edit>,
    pub frame_changed: bool,
}

/// A keyboard. Kotlin calls it from the main thread; the mutex makes it safe to share anyway.
#[derive(Debug, uniffi::Object)]
pub struct KeyboardEngine {
    keyboard: Mutex<keyboard::Keyboard>,
}

#[uniffi::export]
impl KeyboardEngine {
    /// Creates a keyboard with one of the layouts bundled in the engine.
    ///
    /// # Errors
    ///
    /// When `id` names no bundled layout, or the bundled file is invalid.
    #[uniffi::constructor]
    pub fn with_builtin_layout(id: &str) -> Result<Self, EngineError> {
        let builtin = builtin::ALL
            .iter()
            .find(|layout| layout.id == id)
            .ok_or_else(|| EngineError::UnknownLayout { id: id.to_owned() })?;
        let layout = builtin::layout(*builtin).map_err(|error| EngineError::InvalidLayout {
            id: id.to_owned(),
            reason: error.to_string(),
        })?;
        Ok(Self {
            keyboard: Mutex::new(keyboard::Keyboard::new(layout)),
        })
    }

    /// Sets the key area size in pixels and returns the new frame.
    pub fn resize(&self, width: f32, height: f32) -> KeyboardFrame {
        let mut keyboard = self.lock();
        keyboard.resize(width, height);
        keyboard.frame().into()
    }

    /// Returns to the initial page without shift, for a new text field.
    pub fn reset(&self) -> KeyboardFrame {
        let mut keyboard = self.lock();
        keyboard.reset();
        keyboard.frame().into()
    }

    pub fn frame(&self) -> KeyboardFrame {
        self.lock().frame().into()
    }

    pub fn key_at(&self, x: f32, y: f32) -> Option<KeyId> {
        self.lock()
            .key_at(geometry::Point::new(x, y))
            .map(Into::into)
    }

    /// Presses a key. `time_ms` comes from the input event's monotonic clock.
    pub fn press(&self, key: KeyId, time_ms: u64) -> KeyPress {
        self.lock().press(key.into(), time_ms).into()
    }

    /// Returns whether the frame changed.
    pub fn set_auto_capitalize(&self, active: bool) -> bool {
        self.lock().set_auto_capitalize(active)
    }
}

impl KeyboardEngine {
    /// A panic while the lock was held cannot leave the keyboard in a state worse than a
    /// misdrawn frame, which the next event fixes, so a poisoned lock is recovered rather than
    /// taking the keyboard down.
    fn lock(&self) -> MutexGuard<'_, keyboard::Keyboard> {
        self.keyboard.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl From<keyboard::KeyId> for KeyId {
    fn from(id: keyboard::KeyId) -> Self {
        Self {
            page: id.page,
            index: id.index,
        }
    }
}

impl From<KeyId> for keyboard::KeyId {
    fn from(id: KeyId) -> Self {
        Self {
            page: id.page,
            index: id.index,
        }
    }
}

impl From<geometry::Rect> for KeyRect {
    fn from(rect: geometry::Rect) -> Self {
        Self {
            left: rect.left,
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom,
        }
    }
}

impl From<keyboard::KeyKind> for KeyKind {
    fn from(kind: keyboard::KeyKind) -> Self {
        match kind {
            keyboard::KeyKind::Character => Self::Character,
            keyboard::KeyKind::Shift => Self::Shift,
            keyboard::KeyKind::Backspace => Self::Backspace,
            keyboard::KeyKind::Enter => Self::Enter,
            keyboard::KeyKind::Space => Self::Space,
            keyboard::KeyKind::SwitchPage => Self::SwitchPage,
        }
    }
}

impl From<keyboard::ShiftIndicator> for ShiftIndicator {
    fn from(shift: keyboard::ShiftIndicator) -> Self {
        match shift {
            keyboard::ShiftIndicator::Off => Self::Off,
            keyboard::ShiftIndicator::Once => Self::Once,
            keyboard::ShiftIndicator::Locked => Self::Locked,
        }
    }
}

impl From<keyboard::FrameKey> for FrameKey {
    fn from(key: keyboard::FrameKey) -> Self {
        Self {
            id: key.id.into(),
            kind: key.kind.into(),
            label: key.label,
            rect: key.rect.into(),
            repeatable: key.repeatable,
        }
    }
}

impl From<keyboard::Frame> for KeyboardFrame {
    fn from(frame: keyboard::Frame) -> Self {
        Self {
            page_id: frame.page_id,
            shift: frame.shift.into(),
            row_count: frame.row_count,
            keys: frame.keys.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<keyboard::Edit> for Edit {
    fn from(edit: keyboard::Edit) -> Self {
        match edit {
            keyboard::Edit::Commit(text) => Self::Commit { text },
            keyboard::Edit::DeleteBackward => Self::DeleteBackward,
            keyboard::Edit::Enter => Self::Enter,
        }
    }
}

impl From<keyboard::Press> for KeyPress {
    fn from(press: keyboard::Press) -> Self {
        Self {
            edits: press.edits.into_iter().map(Into::into).collect(),
            frame_changed: press.frame_changed,
        }
    }
}
