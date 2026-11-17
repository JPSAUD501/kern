//! Layouts shipped inside the engine.

use crate::layout::{Layout, LayoutError};

/// Identifies a layout bundled with the engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BuiltinLayout {
    pub id: &'static str,
    json: &'static str,
}

pub const QWERTY: BuiltinLayout = BuiltinLayout {
    id: "qwerty",
    json: include_str!("../layouts/qwerty.json"),
};

pub const ALL: &[BuiltinLayout] = &[QWERTY];

/// Parses a bundled layout.
///
/// # Errors
///
/// Returns the validation error if the bundled file is invalid, which the tests rule out.
pub fn layout(builtin: BuiltinLayout) -> Result<Layout, LayoutError> {
    Layout::from_json(builtin.json)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_builtin_layout_is_valid_and_matches_its_id() {
        for builtin in ALL {
            let layout = layout(*builtin).unwrap_or_else(|error| panic!("{}: {error}", builtin.id));
            assert_eq!(layout.id(), builtin.id);
        }
    }
}
