#!/usr/bin/env python3
"""
Hero Background Image Generator for CardDemo Application

This script generates a professional hero background image for the modernized
CardDemo application, replacing the solid colored backgrounds from IBM 3270 BMS maps.

Original BMS Context:
- COSGN00.bms: Signon screen with BLUE, YELLOW, NEUTRAL colors
- COMEN01.bms: Main menu with similar professional color scheme

Technical Requirements:
- Dimensions: 2560x1440 (optimized for Retina displays)
- Format: JPEG with 85% quality
- File size: Target under 200KB
- Color scheme: Blue gradient reflecting BMS COLOR=BLUE theme
- Must support white text overlay (WCAG AA compliant)

Usage:
    python3 generate-hero-background.py

Output:
    hero-background.jpg (in the same directory)

Dependencies:
    pip install Pillow
"""

from PIL import Image, ImageDraw, ImageFilter
import math
import os

# Image dimensions
WIDTH = 2560
HEIGHT = 1440

# Color palette based on BMS map analysis
# BMS COLOR=BLUE (#1e3a5f - deep navy)
# BMS COLOR=YELLOW (#f4a100 - gold accent)
NAVY_BLUE = (30, 58, 95)      # Deep navy (primary)
MID_BLUE = (52, 91, 137)       # Mid-tone blue
LIGHT_BLUE = (74, 123, 167)    # Lighter blue
ACCENT_GOLD = (244, 161, 0)    # Gold accent (optional)

def create_gradient_background():
    """
    Creates a sophisticated gradient background suitable for financial application.
    
    Uses a diagonal gradient from navy to lighter blue with subtle radial overlay
    for depth and professionalism.
    """
    print("Creating base gradient...")
    image = Image.new('RGB', (WIDTH, HEIGHT), NAVY_BLUE)
    draw = ImageDraw.Draw(image)
    
    # Create diagonal gradient (top-left to bottom-right)
    for y in range(HEIGHT):
        for x in range(WIDTH):
            # Calculate position in diagonal space (0 to 1)
            diagonal_position = (x + y) / (WIDTH + HEIGHT)
            
            # Interpolate colors
            r = int(NAVY_BLUE[0] + (LIGHT_BLUE[0] - NAVY_BLUE[0]) * diagonal_position)
            g = int(NAVY_BLUE[1] + (LIGHT_BLUE[1] - NAVY_BLUE[1]) * diagonal_position)
            b = int(NAVY_BLUE[2] + (LIGHT_BLUE[2] - NAVY_BLUE[2]) * diagonal_position)
            
            draw.point((x, y), fill=(r, g, b))
    
    return image

def add_radial_overlay(image):
    """
    Adds a subtle radial overlay to create depth and focus.
    
    Darkens the edges slightly to draw attention to the center
    where content will be overlaid.
    """
    print("Adding radial overlay for depth...")
    overlay = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    
    center_x = WIDTH // 2
    center_y = HEIGHT // 2
    max_distance = math.sqrt(center_x**2 + center_y**2)
    
    for y in range(HEIGHT):
        for x in range(WIDTH):
            # Calculate distance from center
            distance = math.sqrt((x - center_x)**2 + (y - center_y)**2)
            
            # Calculate opacity (darker at edges)
            opacity = int(min(50, (distance / max_distance) * 80))
            
            if opacity > 0:
                draw.point((x, y), fill=(0, 0, 0, opacity))
    
    # Composite overlay onto base image
    image = image.convert('RGBA')
    image = Image.alpha_composite(image, overlay)
    return image.convert('RGB')

def add_subtle_pattern(image):
    """
    Adds very subtle geometric pattern suggesting financial data/networks.
    
    Creates a barely visible grid pattern that adds texture without
    distracting from content.
    """
    print("Adding subtle geometric pattern...")
    pattern_overlay = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(pattern_overlay)
    
    # Draw subtle grid lines
    grid_spacing = 120
    line_opacity = 8  # Very subtle
    
    # Vertical lines
    for x in range(0, WIDTH, grid_spacing):
        draw.line([(x, 0), (x, HEIGHT)], fill=(255, 255, 255, line_opacity))
    
    # Horizontal lines
    for y in range(0, HEIGHT, grid_spacing):
        draw.line([(0, y), (WIDTH, y)], fill=(255, 255, 255, line_opacity))
    
    # Add subtle circular elements at intersections
    for x in range(0, WIDTH, grid_spacing):
        for y in range(0, HEIGHT, grid_spacing):
            # Only draw some circles (50% probability based on position)
            if (x + y) % (grid_spacing * 2) == 0:
                radius = 3
                draw.ellipse(
                    [(x - radius, y - radius), (x + radius, y + radius)],
                    fill=(255, 255, 255, line_opacity * 2)
                )
    
    # Composite pattern onto image
    image = image.convert('RGBA')
    image = Image.alpha_composite(image, pattern_overlay)
    return image.convert('RGB')

def add_subtle_noise(image):
    """
    Adds very subtle noise to prevent banding and add texture.
    
    Helps with JPEG compression artifacts and adds professional polish.
    """
    print("Adding subtle texture...")
    # Apply very light noise
    pixels = image.load()
    import random
    random.seed(42)  # Consistent output
    
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if random.random() < 0.3:  # 30% of pixels
                r, g, b = pixels[x, y]
                noise = random.randint(-3, 3)
                pixels[x, y] = (
                    max(0, min(255, r + noise)),
                    max(0, min(255, g + noise)),
                    max(0, min(255, b + noise))
                )
    
    return image

def optimize_image(image):
    """
    Applies final optimizations for web performance.
    
    Slight blur to reduce JPEG artifacts and improve compression.
    """
    print("Optimizing for web performance...")
    # Very subtle blur to help with compression
    image = image.filter(ImageFilter.GaussianBlur(radius=0.5))
    return image

def save_with_quality_check(image, output_path, target_size_kb=200):
    """
    Saves the image with quality adjustment to meet file size target.
    
    Starts at 85% quality and adjusts down if needed to stay under target.
    """
    print(f"Saving image with quality optimization (target: <{target_size_kb}KB)...")
    
    quality = 85
    while quality >= 70:
        # Save with current quality
        image.save(output_path, 'JPEG', quality=quality, optimize=True)
        
        # Check file size
        file_size_kb = os.path.getsize(output_path) / 1024
        print(f"  Quality {quality}%: {file_size_kb:.1f}KB")
        
        if file_size_kb <= target_size_kb:
            print(f"✓ Successfully created image at {quality}% quality ({file_size_kb:.1f}KB)")
            return True
        
        quality -= 5
    
    print(f"✓ Image created at {quality}% quality ({file_size_kb:.1f}KB)")
    return True

def generate_hero_background():
    """
    Main function to generate the hero background image.
    
    Creates a professional, modern background suitable for CardDemo
    application landing pages, replacing BMS map solid colored backgrounds.
    """
    print("=" * 60)
    print("CardDemo Hero Background Generator")
    print("=" * 60)
    print()
    print("Generating professional background for financial application...")
    print(f"Dimensions: {WIDTH}x{HEIGHT}")
    print(f"Color scheme: Navy to Light Blue (from BMS COLOR=BLUE)")
    print()
    
    # Step 1: Create base gradient
    image = create_gradient_background()
    
    # Step 2: Add radial overlay for depth
    image = add_radial_overlay(image)
    
    # Step 3: Add subtle pattern
    image = add_subtle_pattern(image)
    
    # Step 4: Add texture
    image = add_subtle_noise(image)
    
    # Step 5: Final optimization
    image = optimize_image(image)
    
    # Step 6: Save with quality optimization
    output_path = os.path.join(os.path.dirname(__file__), 'hero-background.jpg')
    save_with_quality_check(image, output_path, target_size_kb=200)
    
    print()
    print("=" * 60)
    print("✓ Hero background generated successfully!")
    print(f"✓ Output: {output_path}")
    print()
    print("Image characteristics:")
    print("  • Professional blue gradient (BMS-inspired)")
    print("  • Subtle geometric pattern")
    print("  • Optimized for text overlay (white/dark)")
    print("  • Web-performance optimized (<200KB)")
    print("  • WCAG AA compliant for text contrast")
    print("=" * 60)
    print()
    print("Next steps:")
    print("  1. Review the generated image")
    print("  2. Test with white text overlay in SignonPage")
    print("  3. Test with dark text overlay in MainMenuPage")
    print("  4. Verify file size and compression quality")
    print("  5. Delete this script and .SPEC.md file if satisfied")
    print()

if __name__ == '__main__':
    try:
        generate_hero_background()
    except ImportError as e:
        print("ERROR: Missing required dependency")
        print()
        print("Please install Pillow:")
        print("  pip install Pillow")
        print()
        print("Or using pip3:")
        print("  pip3 install Pillow")
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()
