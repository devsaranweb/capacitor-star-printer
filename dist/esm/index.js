import { registerPlugin } from '@capacitor/core';
const StarPrinter = registerPlugin('StarPrinter', {
    web: () => import('./web').then((m) => new m.StarPrinterWeb()),
});
export * from './definitions';
export { StarPrinter };
