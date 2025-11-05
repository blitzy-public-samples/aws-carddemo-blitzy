/**
 * PostCSS Configuration for OCR Processing Application
 * 
 * This configuration enables TailwindCSS and Autoprefixer for the frontend application.
 * PostCSS processes the CSS and applies transformations like vendor prefixing and
 * TailwindCSS utility class generation.
 * 
 * @see https://postcss.org/
 * @see https://tailwindcss.com/docs/installation/using-postcss
 */

module.exports = {
  plugins: {
    // TailwindCSS plugin - processes utility classes and generates final CSS
    tailwindcss: {},
    
    // Autoprefixer plugin - adds vendor prefixes for better browser compatibility
    autoprefixer: {},
  },
}
