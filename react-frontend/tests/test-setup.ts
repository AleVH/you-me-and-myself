/**
 * Global test setup — runs before every test file.
 *
 * Imports the @testing-library/jest-dom matchers so assertions like
 * toBeInTheDocument(), toBeDisabled(), toHaveClass(), etc. are
 * available in every test without explicit imports.
 *
 * Think of this as a "beforeAll that applies to the entire suite."
 *
 * @see vite.config.ts — references this file in test.setupFiles
 * @see https://testing-library.com/docs/ecosystem-jest-dom/
 */
import "@testing-library/jest-dom";
