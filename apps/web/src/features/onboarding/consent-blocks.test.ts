import { describe, expect, it } from 'vitest';
import { toConsentBlocks } from './consent-blocks';

describe('toConsentBlocks', () => {
  it('splits headings, paragraphs and lists into text blocks', () => {
    expect(
      toConsentBlocks(
        '# Title\n\nFirst paragraph.\n\n- one\n- two\n\n1. a\n2) b\n\nLast <b>para</b>.\n',
      ),
    ).toEqual([
      { kind: 'heading', text: 'Title' },
      { kind: 'paragraph', text: 'First paragraph.' },
      { kind: 'list', items: ['one', 'two'] },
      { kind: 'list', items: ['a', 'b'] },
      { kind: 'paragraph', text: 'Last <b>para</b>.' },
    ]);
  });

  it('keeps a mixed block as a paragraph and drops empty blocks', () => {
    expect(toConsentBlocks('\n\nText\n- not a list\n\n\n')).toEqual([
      { kind: 'paragraph', text: 'Text\n- not a list' },
    ]);
  });
});
