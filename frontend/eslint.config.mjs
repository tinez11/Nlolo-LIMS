import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/types/api/**', 'playwright-report', 'test-results'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // Money is a decimal string platform-wide; parseFloat on currency is how you
      // ship a payout that is off by a cent.
      'no-restricted-globals': [
        'error',
        { name: 'parseFloat', message: 'Money is a decimal string. Use lib/money helpers.' },
      ],
    },
  },
  {
    /*
      The field treatment belongs to `components/ui/input`, and nowhere else.

      It has been copy-pasted back into feature files four times now -- FormField
      (12 files), Panel (8), FilterChip (9), and finally 136 input class strings
      in 24 variants. Each recurrence cost more than the last, because the
      accessibility wiring those controls need (aria-invalid, aria-describedby,
      the id a <label htmlFor> points at) is not something anybody makes 136
      times by hand.

      So this is a guard against the fifth time rather than a style preference.
      `components/` is exempt: `ui/input` IS the treatment, and DatePicker and
      PartyPicker draw their own trigger to match it.

      Deliberately keyed on the class rather than on `<input>` as an element.
      Five raw inputs survive in features and all five are correct -- three
      checkboxes, which are not text fields, and two react-dropzone inputs,
      which must be raw for `getInputProps()` to attach.
    */
    files: ['src/features/**/*.tsx'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'JSXAttribute[name.name="className"] Literal[value=/border-input/]',
          message:
            'Use Input, Select or Textarea from @/components/ui/input. A hand-rolled field misses the id, aria-invalid and aria-describedby that FormField supplies through context.',
        },
        {
          selector: 'JSXAttribute[name.name="className"] TemplateElement[value.raw=/border-input/]',
          message:
            'Use Input, Select or Textarea from @/components/ui/input. A hand-rolled field misses the id, aria-invalid and aria-describedby that FormField supplies through context.',
        },
      ],
    },
  },
  {
    files: ['scripts/**/*.mjs', '*.config.{ts,mjs}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
);
