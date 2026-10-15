import { useParams } from 'react-router';
import { PageSkeleton } from '../../components/ui/PageSkeleton';

export const DecisionPage = () => {
  const { decisionId } = useParams<{ decisionId: string }>();
  return (
    <PageSkeleton
      eyebrow="Decision record"
      title="Why this route?"
      lede="Every recommendation keeps a record of what was known when it was made."
      plannedIn="BC-0117"
    >
      <p className="page__status">
        Decision <code>{decisionId}</code>
      </p>
    </PageSkeleton>
  );
};
