import { useRef, useState } from 'react';
import { StyleSheet, View, type TextInputInstance } from 'react-native';
import { fieldErrorsFrom } from '@web/components/ui/field-errors';
import { isApiError } from '../../../api/errors';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { AppText } from '../../../components/ui/AppText';
import { Button } from '../../../components/ui/Button';
import { TextField } from '../../../components/ui/TextField';
import { SPACE } from '../../../theme/theme';
import { useAuthenticate, type AuthMode } from '../../auth/useAuthenticate';
import { PASSWORD_MAX, PASSWORD_MIN, validateAccount } from '../steps';

const COPY: Record<
  AuthMode,
  { submit: string; hint: string; other: string; otherAction: string }
> = {
  register: {
    submit: 'Create account',
    hint: 'A username is the only identifier Beacon keeps. Nothing about your body is asked.',
    other: 'Already have one?',
    otherAction: 'Sign in instead',
  },
  'sign-in': {
    submit: 'Sign in',
    hint: 'Sign in with the username and password you chose before.',
    other: 'New here?',
    otherAction: 'Create an account instead',
  },
};

/** Step 1: register (default) or sign in; 409 USERNAME_TAKEN offers to switch to sign-in. */
export const AccountStep = ({
  onAuthenticated,
}: {
  onAuthenticated: () => void;
}) => {
  const [mode, setMode] = useState<AuthMode>('register');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [clientErrors, setClientErrors] = useState<{
    username?: string;
    password?: string;
  }>({});
  const passwordRef = useRef<TextInputInstance>(null);
  const authenticate = useAuthenticate();

  const serverErrors = fieldErrorsFrom(authenticate.error, [
    'username',
    'password',
  ]);
  const isTaken =
    isApiError(authenticate.error) &&
    authenticate.error.code === 'USERNAME_TAKEN';
  const usernameError =
    clientErrors.username ??
    serverErrors.username ??
    (isTaken ? 'That username is already registered.' : null);
  const passwordError = clientErrors.password ?? serverErrors.password ?? null;
  const showBanner =
    authenticate.isError && !isTaken && Object.keys(serverErrors).length === 0;
  const copy = COPY[mode];

  const submit = () => {
    const problems = validateAccount(username, password);
    setClientErrors(problems);
    if (problems.username || problems.password) return;
    authenticate.mutate(
      { mode, username, password },
      { onSuccess: onAuthenticated },
    );
  };

  const switchMode = (next: AuthMode) => {
    setMode(next);
    authenticate.reset();
    if (next === 'sign-in') passwordRef.current?.focus();
  };

  return (
    <View style={styles.form}>
      <AppText tone="muted">{copy.hint}</AppText>
      {showBanner ? <ApiErrorBanner error={authenticate.error} /> : null}
      <TextField
        label="Username"
        value={username}
        onChangeText={(text) => {
          setUsername(text);
          setClientErrors(({ username: _drop, ...rest }) => rest);
        }}
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="username"
        textContentType="username"
        returnKeyType="next"
        onSubmitEditing={() => passwordRef.current?.focus()}
        error={usernameError}
        testID="username"
      />
      {isTaken && mode === 'register' ? (
        <Button
          label="Sign in instead"
          variant="quiet"
          onPress={() => switchMode('sign-in')}
        />
      ) : null}
      <TextField
        ref={passwordRef}
        label="Password"
        value={password}
        onChangeText={(text) => {
          setPassword(text);
          setClientErrors(({ password: _drop, ...rest }) => rest);
        }}
        secureTextEntry
        autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
        textContentType={mode === 'register' ? 'newPassword' : 'password'}
        returnKeyType="go"
        onSubmitEditing={submit}
        hint={`${PASSWORD_MIN} to ${PASSWORD_MAX} characters.`}
        error={passwordError}
        testID="password"
      />
      <Button
        label={copy.submit}
        onPress={submit}
        busy={authenticate.isPending}
        testID="account-submit"
      />
      <View style={styles.switchRow}>
        <AppText variant="small" tone="muted">
          {copy.other}
        </AppText>
        <Button
          label={copy.otherAction}
          variant="quiet"
          onPress={() =>
            switchMode(mode === 'register' ? 'sign-in' : 'register')
          }
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  form: { gap: SPACE.md },
  switchRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
});
