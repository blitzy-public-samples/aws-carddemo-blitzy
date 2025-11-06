import { IsNotEmpty, IsNumber, Min } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

/**
 * ZoneCoordinatesDto - Data Transfer Object for bounding box coordinates defining document extraction zones.
 * 
 * Defines a rectangular zone on a document page where OCR data extraction should occur.
 * Coordinates are relative to the page dimensions with (0,0) as the top-left corner.
 * Units are typically in points (1/72 inch) or pixels depending on document resolution.
 * 
 * Coordinate System:
 * - Origin (0,0) is at the top-left corner of the page
 * - X increases from left to right
 * - Y increases from top to bottom
 * - Width and height must be positive values
 * 
 * Visual Representation:
 * ```
 * (0,0) -----------------> X axis
 *   |
 *   |    (x,y) +--------+
 *   |          |  ZONE  | height
 *   |          +--------+
 *   |            width
 *   v
 *  Y axis
 * ```
 * 
 * Usage in Template Builder:
 * 1. User selects zone on document image via visual canvas tool
 * 2. Frontend calculates bounding box coordinates
 * 3. Coordinates sent to backend in CreateTemplateDto
 * 4. Validated using this DTO
 * 5. Stored in TemplateField entity (x, y, width, height columns)
 * 6. Used by OCR Processing Service to extract text from specific zones
 * 
 * Validation Rules:
 * - All coordinates must be non-negative numbers
 * - Width and height must be greater than zero
 * - Coordinates are decimal numbers for precision
 * - No maximum bounds enforced (varies by document size)
 * 
 * Examples:
 * ```typescript
 * // Invoice number zone in top-right corner
 * {
 *   x: 400.0,
 *   y: 50.0,
 *   width: 150.0,
 *   height: 30.0
 * }
 * 
 * // Total amount zone in bottom-right corner
 * {
 *   x: 450.0,
 *   y: 700.0,
 *   width: 100.0,
 *   height: 25.0
 * }
 * ```
 * 
 * Integration:
 * - Used in TemplateFieldDto.coordinates property
 * - Validated during template creation/update
 * - Stored in TemplateField entity columns (x, y, width, height)
 * - Used by OCR service to crop document zones for extraction
 * 
 * @see TemplateFieldDto - Parent DTO containing zone coordinates
 * @see TemplateField entity - Stores coordinates in database
 * @see Section 0.5.7 - Phase 7 Visual Template Builder with zone selection
 * @see Section 0.7.2 - NestJS DTO validation with class-validator
 */
export class ZoneCoordinatesDto {
  /**
   * X coordinate of zone top-left corner relative to page origin.
   * 
   * Represents the horizontal position from the left edge of the page.
   * Value must be a non-negative number (0 or greater).
   * Typically measured in points (1/72 inch) or pixels.
   * 
   * @example 100.50
   */
  @ApiProperty({
    description: 'X coordinate of zone top-left corner relative to page',
    example: 100.5,
    minimum: 0,
  })
  @IsNotEmpty({ message: 'X coordinate is required' })
  @IsNumber({}, { message: 'X coordinate must be a number' })
  @Min(0, { message: 'X coordinate must be >= 0' })
  x!: number;

  /**
   * Y coordinate of zone top-left corner relative to page origin.
   * 
   * Represents the vertical position from the top edge of the page.
   * Value must be a non-negative number (0 or greater).
   * Typically measured in points (1/72 inch) or pixels.
   * 
   * @example 200.75
   */
  @ApiProperty({
    description: 'Y coordinate of zone top-left corner relative to page',
    example: 200.75,
    minimum: 0,
  })
  @IsNotEmpty({ message: 'Y coordinate is required' })
  @IsNumber({}, { message: 'Y coordinate must be a number' })
  @Min(0, { message: 'Y coordinate must be >= 0' })
  y!: number;

  /**
   * Width of the extraction zone in document units.
   * 
   * Represents the horizontal extent of the zone from the x coordinate.
   * Value must be a positive number (greater than 0).
   * Typically measured in points (1/72 inch) or pixels.
   * 
   * @example 150.0
   */
  @ApiProperty({
    description: 'Zone width in document units (points or pixels)',
    example: 150.0,
    minimum: 1,
  })
  @IsNotEmpty({ message: 'Width is required' })
  @IsNumber({}, { message: 'Width must be a number' })
  @Min(1, { message: 'Width must be > 0' })
  width!: number;

  /**
   * Height of the extraction zone in document units.
   * 
   * Represents the vertical extent of the zone from the y coordinate.
   * Value must be a positive number (greater than 0).
   * Typically measured in points (1/72 inch) or pixels.
   * 
   * @example 50.0
   */
  @ApiProperty({
    description: 'Zone height in document units (points or pixels)',
    example: 50.0,
    minimum: 1,
  })
  @IsNotEmpty({ message: 'Height is required' })
  @IsNumber({}, { message: 'Height must be a number' })
  @Min(1, { message: 'Height must be > 0' })
  height!: number;
}
