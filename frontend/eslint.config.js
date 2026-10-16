import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';

export default [
  { ignores: ['dist/**', 'coverage/**', 'dist-sbom/**', 'node_modules/**', '.scannerwork/**'] },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx,mjs}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: { ...globals.browser },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      eqeqeq: ['error', 'always'],
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'dangerouslySetInnerHTML is banned (threat T13): render values as text.',
        },
        {
          selector: "MemberExpression[property.name='innerHTML']",
          message: 'innerHTML is banned (threat T13): render values as text.',
        },
        {
          selector: "MemberExpression[property.name='outerHTML']",
          message: 'outerHTML is banned (threat T13).',
        },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'localStorage', message: 'No result data or secrets in browser storage (SEC-08).' },
        { name: 'sessionStorage', message: 'No result data or secrets in browser storage (SEC-08).' },
      ],
    },
  },
  {
    files: ['**/*.test.{js,jsx}', 'src/test/**', 'src/mocks/**'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  {
    files: ['vite.config.js', 'eslint.config.js', 'scripts/**'],
    languageOptions: { globals: { ...globals.node } },
  },
];
