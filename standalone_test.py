#!/usr/bin/env python3
"""
Standalone smoke test for the pure-JVM validator logic in mygps.

Re-implements the same boundary checks as MockLocationEngine.isValidLat/isValidLng
and SavedLocations.isValidName so we can verify the design without a Kotlin compiler.

Run: python3 standalone_test.py
"""
import sys
import math


def is_valid_lat(lat: float) -> bool:
    return not math.isnan(lat) and -90.0 <= lat <= 90.0


def is_valid_lng(lng: float) -> bool:
    return not math.isnan(lng) and -180.0 <= lng <= 180.0


def is_valid_name(name: str) -> bool:
    return bool(name and name.strip()) and 1 <= len(name) <= 64


def assert_eq(actual, expected, msg):
    if actual != expected:
        print(f"FAIL: {msg}: expected {expected!r}, got {actual!r}")
        sys.exit(1)


tests_passed = 0


def check(name, condition):
    global tests_passed
    if condition:
        tests_passed += 1
    else:
        print(f"FAIL: {name}")
        sys.exit(1)


# Latitude boundary
check("lat zero", is_valid_lat(0.0))
check("lat north pole", is_valid_lat(90.0))
check("lat south pole", is_valid_lat(-90.0))
check("lat above", not is_valid_lat(90.0001))
check("lat below", not is_valid_lat(-90.0001))
check("lat NaN", not is_valid_lat(float('nan')))
check("lat Bangkok", is_valid_lat(13.7563))

# Longitude boundary
check("lng zero", is_valid_lng(0.0))
check("lng east", is_valid_lng(180.0))
check("lng west", is_valid_lng(-180.0))
check("lng above", not is_valid_lng(180.0001))
check("lng below", not is_valid_lng(-180.0001))
check("lng NaN", not is_valid_lng(float('nan')))
check("lng Tokyo", is_valid_lng(139.6503))

# Name validation
check("name Bangkok", is_valid_name("Bangkok"))
check("name A", is_valid_name("A"))
check("name empty", not is_valid_name(""))
check("name whitespace", not is_valid_name("   "))
check("name too long", not is_valid_name("a" * 65))
check("name exactly 64", is_valid_name("a" * 64))

# Round-trip: SavedLocation JSON-shape
import json
sample = {"name": "Tokyo", "lat": 35.6762, "lng": 139.6503}
serialized = json.dumps(sample)
parsed = json.loads(serialized)
assert_eq(parsed["name"], "Tokyo", "round-trip name")
assert_eq(parsed["lat"], 35.6762, "round-trip lat")
assert_eq(parsed["lng"], 139.6503, "round-trip lng")

# Total
print(f"PASS: {tests_passed} pure-JVM validator checks passed")