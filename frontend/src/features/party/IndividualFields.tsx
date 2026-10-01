import { Controller, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { Input, Select } from '@/components/ui/input';
import type { RegisterIndividualFormValues } from './registerIndividualForm';

/**
 * The person, as a set of form controls.
 *
 * <p>Shared by registration and by correction, and shared rather than copied for a reason this
 * console has already been bitten by: e2e specs address these controls by their ACCESSIBLE NAME,
 * so two copies of seventeen labelled inputs drift, and the drift shows up as a browser test
 * failing on a page nobody edited. One definition means "Smoker status" is the same control in
 * both places or in neither.
 *
 * <p>Deliberately dumb — it renders fields and nothing else. No submit button, no store, no
 * request: registering a person and correcting one are different acts with different gates,
 * different verbs and different words on the button, and folding those into a `mode` prop would
 * hide the one difference that matters behind the one thing that does not.
 */
export function IndividualFields({
  register,
  control,
  errors,
}: {
  register: UseFormRegister<RegisterIndividualFormValues>;
  control: Control<RegisterIndividualFormValues>;
  errors: FieldErrors<RegisterIndividualFormValues>;
}) {
  return (
    <>
      <FormField label="Full name" error={errors.fullName?.message}>
        <Input placeholder="Amina Hassan" {...register('fullName')} />
      </FormField>

      <FormField label="Date of birth" error={errors.dateOfBirth?.message}>
        <Controller
          control={control}
          name="dateOfBirth"
          render={({ field }) => (
            <DatePicker
              value={field.value || null}
              onChange={(iso) => field.onChange(iso ?? '')}
              disabled={{ after: new Date() }}
            />
          )}
        />
      </FormField>

      <FormField label="Phone number (optional)" error={errors.phoneNumber?.message}>
        <Input placeholder="+255712345678" {...register('phoneNumber')} />
      </FormField>

      <FormField label="Email (optional)" error={errors.email?.message}>
        <Input placeholder="amina@example.tz" {...register('email')} />
      </FormField>

      {/* Everything below is optional, and grouped rather than run on as one flat
          list of thirteen more inputs. The groups are the order a person is
          actually asked: who they are, what they do, where they live. */}
      <FieldGroup
        title="Identity"
        hint="One document per person. A national ID already on the register is refused."
      >
        <div className="grid grid-cols-2 gap-3">
          <FormField label="ID type" error={errors.idType?.message}>
            <Select {...register('idType')}>
              <option value="">Not recorded</option>
              <option value="NATIONAL_ID">National ID</option>
              <option value="PASSPORT">Passport</option>
              <option value="DRIVING_LICENCE">Driving licence</option>
              <option value="VOTER_ID">Voter ID</option>
            </Select>
          </FormField>

          <FormField label="ID number" error={errors.idNumber?.message}>
            <Input {...register('idNumber')} />
          </FormField>

          <FormField label="Nationality" error={errors.nationality?.message}>
            <Input className="uppercase" placeholder="TZ" {...register('nationality')} />
          </FormField>

          {/*
            Beside the ID rather than beside the name, because it is an identifier and a reader
            looking for "which number is this person" should find all of them together. The hint
            says whose number it is: this is the one place somebody could mistake it for the
            platform's own id, and a reference filled in with a party id reconciles nothing.
          */}
          <FormField label="Client reference (optional)" error={errors.clientReference?.message}>
            <Input {...register('clientReference')} />
            <p className="mt-1 text-xs text-subtle-foreground">
              Your own number for this client, if they already have one. Used to reconcile
              against your existing records; leave blank if there is none.
            </p>
          </FormField>
        </div>
      </FieldGroup>

      <FieldGroup
        title="Person"
        // Not decoration: sex and smoker status, with the date of birth above,
        // are exactly the key of the product's base rate table. A party
        // registered without them cannot be priced from its own record.
        hint="Sex and smoker status are rating factors — a quote needs them."
      >
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Sex" error={errors.sex?.message}>
            <Select {...register('sex')}>
              <option value="">Not recorded</option>
              <option value="FEMALE">Female</option>
              <option value="MALE">Male</option>
            </Select>
          </FormField>

          <FormField label="Smoker status" error={errors.smokerStatus?.message}>
            <Select {...register('smokerStatus')}>
              {/* "Not recorded" and "Asked, declined to say" are genuinely
                  different answers and a product may price them differently. */}
              <option value="">Not recorded</option>
              <option value="NON_SMOKER">Non-smoker</option>
              <option value="SMOKER">Smoker</option>
              <option value="UNKNOWN">Asked, declined to say</option>
            </Select>
          </FormField>

          <FormField label="Occupation" error={errors.occupation?.message}>
            <Input placeholder="As the applicant describes it" {...register('occupation')} />
          </FormField>

          <FormField label="Occupation class" error={errors.occupationClass?.message}>
            <Input placeholder="Rating band" {...register('occupationClass')} />
          </FormField>

          <div className="col-span-2">
            <FormField label="Employer" error={errors.employerName?.message}>
              <Input {...register('employerName')} />
            </FormField>
          </div>
        </div>
      </FieldGroup>

      <FieldGroup title="Address">
        <div className="grid grid-cols-2 gap-3">
          <div className="col-span-2">
            <FormField label="Street or plot" error={errors.addressLine?.message}>
              <Input {...register('addressLine')} />
            </FormField>
          </div>

          <FormField label="Ward" error={errors.ward?.message}>
            <Input {...register('ward')} />
          </FormField>

          <FormField label="District" error={errors.district?.message}>
            <Input {...register('district')} />
          </FormField>

          <FormField label="Region" error={errors.region?.message}>
            <Input {...register('region')} />
          </FormField>

          <FormField label="Postal code" error={errors.postalCode?.message}>
            <Input {...register('postalCode')} />
          </FormField>
        </div>
      </FieldGroup>
    </>
  );
}

/**
 * A titled group of optional fields inside a form.
 *
 * A real `<fieldset>`/`<legend>`, not a styled div: the legend names the group to a
 * screen reader as it enters, which is the whole reason to group thirteen optional
 * inputs rather than run them together. The hairline-and-caption treatment matches
 * the console's panels without borrowing the `Panel` silhouette, which means
 * something else here.
 */
export function FieldGroup({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <fieldset className="border-t border-border pt-3">
      <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
        {title}
      </legend>
      {hint && <p className="mb-2.5 text-xs text-muted-foreground">{hint}</p>}
      {children}
    </fieldset>
  );
}
