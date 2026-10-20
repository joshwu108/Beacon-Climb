import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { themeFor, type ColorSchemeName, type Theme } from './theme';

const ThemeContext = createContext<Theme>(themeFor('light'));

export interface ThemeProviderProps {
  readonly children: ReactNode;
  /** Force a scheme (tests, previews); otherwise follow the system setting. */
  readonly scheme?: ColorSchemeName;
}

/** Light (cream paper) or dark (lamp-lit crag) from the system setting. */
export const ThemeProvider = ({ children, scheme }: ThemeProviderProps) => {
  const system = useColorScheme();
  const resolved: ColorSchemeName =
    scheme ?? (system === 'dark' ? 'dark' : 'light');
  const theme = useMemo(() => themeFor(resolved), [resolved]);
  return (
    <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>
  );
};

export const useTheme = (): Theme => useContext(ThemeContext);
