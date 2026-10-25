import nx from '@nx/eslint-plugin';

export default [
  ...nx.configs['flat/base'],
  ...nx.configs['flat/typescript'],
  ...nx.configs['flat/javascript'],
  {
    ignores: [
      '**/dist',
      '**/out-tsc',
      '**/vitest.config.*.timestamp*',
      '**/vite.config.*.timestamp*',
    ],
  },
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {
      '@nx/enforce-module-boundaries': [
        'error',
        {
          enforceBuildableLibDependency: true,
          allow: ['^.*/eslint(\\.base)?\\.config\\.[cm]?[jt]s$'],
          depConstraints: [
            // Apps never import other apps; they may use UI libs, tokens and contracts.
            {
              sourceTag: 'type:app',
              onlyDependOnLibsWithTags: [
                'type:ui',
                'type:tokens',
                'type:contracts',
                'type:data-access',
              ],
            },
            // Framework-agnostic clients (fetch + centrifuge): no UI framework runtime.
            {
              sourceTag: 'type:data-access',
              onlyDependOnLibsWithTags: [],
              bannedExternalImports: ['react', 'react-dom', 'next', 'next/*', 'lit', 'lit/*'],
            },
            { sourceTag: 'type:ui', onlyDependOnLibsWithTags: ['type:tokens'] },
            { sourceTag: 'type:tokens', onlyDependOnLibsWithTags: [] },
            { sourceTag: 'type:contracts', onlyDependOnLibsWithTags: [] },
            {
              sourceTag: 'type:e2e',
              onlyDependOnLibsWithTags: ['type:contracts'],
            },
            // React and Lit/web code never share a runtime: only shared tokens cross the boundary.
            {
              sourceTag: 'platform:react',
              onlyDependOnLibsWithTags: ['platform:react', 'platform:shared'],
            },
            {
              sourceTag: 'platform:web',
              onlyDependOnLibsWithTags: ['platform:web', 'platform:shared'],
              bannedExternalImports: [
                'react',
                'react-dom',
                'react/*',
                'react-dom/*',
                'next',
                'next/*',
              ],
            },
          ],
        },
      ],
    },
  },
  {
    files: [
      '**/*.ts',
      '**/*.tsx',
      '**/*.cts',
      '**/*.mts',
      '**/*.js',
      '**/*.jsx',
      '**/*.cjs',
      '**/*.mjs',
    ],
    // Override or add rules here
    rules: {},
  },
];
