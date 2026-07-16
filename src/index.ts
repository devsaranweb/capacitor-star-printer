import { registerPlugin } from '@capacitor/core';

import type { StarPrinterPlugin } from './definitions';

const StarPrinter = registerPlugin<StarPrinterPlugin>('StarPrinter', {
  web: () => import('./web').then((m) => new m.StarPrinterWeb()),
});

export * from './definitions';
export { StarPrinter };
