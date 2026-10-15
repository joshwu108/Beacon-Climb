export type ConsentBlock =
  | { readonly kind: 'heading'; readonly text: string }
  | { readonly kind: 'paragraph'; readonly text: string }
  | { readonly kind: 'list'; readonly items: readonly string[] };

const LIST_LINE = /^\s*(?:[-*+]|\d+[.)])\s+/;

/**
 * Split the consent Markdown into blocks rendered as text nodes (no HTML injection):
 * a leading "# " line is a heading, lines starting with "-", "*" or "1." form a list,
 * anything else is a paragraph. Inline Markdown is left as typed.
 */
export const toConsentBlocks = (text: string): ConsentBlock[] =>
  text
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter((block) => block.length > 0)
    .map((block): ConsentBlock => {
      if (block.startsWith('# '))
        return { kind: 'heading', text: block.slice(2) };
      const lines = block.split('\n');
      if (lines.every((line) => LIST_LINE.test(line))) {
        return {
          kind: 'list',
          items: lines.map((line) => line.replace(LIST_LINE, '').trim()),
        };
      }
      return { kind: 'paragraph', text: block };
    });
