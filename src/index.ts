import { registerPlugin } from '@capacitor/core';

import type { MatrixPlugin } from './definitions';

const Matrix = registerPlugin<MatrixPlugin>('Matrix', {
  web: () => import('./web').then((m) => new m.MatrixWeb()),
});

export * from './definitions';
export { Matrix };
