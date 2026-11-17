//! Plane geometry in keyboard pixels, with the origin at the keyboard's top left.

/// A point in keyboard pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

impl Point {
    pub const fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }
}

/// An axis-aligned rectangle. `left`/`top` are inclusive, `right`/`bottom` exclusive.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Rect {
    pub const ZERO: Self = Self::new(0.0, 0.0, 0.0, 0.0);

    pub const fn new(left: f32, top: f32, right: f32, bottom: f32) -> Self {
        Self {
            left,
            top,
            right,
            bottom,
        }
    }

    pub fn width(&self) -> f32 {
        self.right - self.left
    }

    pub fn height(&self) -> f32 {
        self.bottom - self.top
    }

    pub fn contains(&self, point: Point) -> bool {
        point.x >= self.left && point.x < self.right && point.y >= self.top && point.y < self.bottom
    }

    /// Squared distance from `point` to the nearest point of the rectangle; zero inside it.
    pub fn distance_squared_to(&self, point: Point) -> f32 {
        let dx = (self.left - point.x).max(point.x - self.right).max(0.0);
        let dy = (self.top - point.y).max(point.y - self.bottom).max(0.0);
        dx * dx + dy * dy
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distance_is_zero_inside_and_grows_outside() {
        let rect = Rect::new(10.0, 10.0, 20.0, 20.0);
        assert!(rect.distance_squared_to(Point::new(15.0, 15.0)).abs() < f32::EPSILON);
        assert!((rect.distance_squared_to(Point::new(5.0, 15.0)) - 25.0).abs() < f32::EPSILON);
        assert!((rect.distance_squared_to(Point::new(23.0, 24.0)) - 25.0).abs() < f32::EPSILON);
    }

    #[test]
    fn right_and_bottom_edges_belong_to_the_next_rectangle() {
        let rect = Rect::new(0.0, 0.0, 10.0, 10.0);
        assert!(rect.contains(Point::new(0.0, 0.0)));
        assert!(!rect.contains(Point::new(10.0, 5.0)));
        assert!(!rect.contains(Point::new(5.0, 10.0)));
    }
}
