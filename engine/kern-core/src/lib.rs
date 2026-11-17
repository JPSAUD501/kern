//! Kern's typing engine.
//!
//! This crate is platform independent: the Android app reaches it through `kern-ffi`, and the
//! evaluation harness links it directly, so what is measured is exactly what ships.

#![forbid(unsafe_code)]

pub mod builtin;
pub mod geometry;
pub mod keyboard;
pub mod layout;
