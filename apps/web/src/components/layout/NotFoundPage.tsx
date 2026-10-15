import { Link } from 'react-router';

export const NotFoundPage = () => (
  <article className="page">
    <p className="page__eyebrow">404</p>
    <h1>This page isn&apos;t available or isn&apos;t yours.</h1>
    <p className="page__lede">
      Check the address, or head back to <Link to="/explore">Explore</Link>.
    </p>
  </article>
);
