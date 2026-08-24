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
    files: ['scripts/**/*.mjs', '*.config.{ts,mjs}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
);
