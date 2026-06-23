import React, { useState } from 'react';
import {
  IonModal, IonHeader, IonToolbar, IonTitle, IonContent,
  IonItem, IonLabel, IonInput, IonButton, IonButtons,
  IonIcon, IonText,
} from '@ionic/react';
import { closeOutline, keyOutline, checkmarkOutline, eyeOutline, eyeOffOutline } from 'ionicons/icons';

interface Props {
  isOpen: boolean;
  currentKey: string;
  onSave: (key: string) => void;
  onClose: () => void;
}

export const ApiKeyModal: React.FC<Props> = ({ isOpen, currentKey, onSave, onClose }) => {
  const [draft, setDraft] = useState(currentKey);
  const [showKey, setShowKey] = useState(false);

  const handleSave = () => {
    if (draft.trim()) {
      onSave(draft.trim());
      onClose();
    }
  };

  return (
    <IonModal isOpen={isOpen} onDidDismiss={onClose}>
      <IonHeader>
        <IonToolbar color="dark">
          <IonTitle>
            <IonIcon icon={keyOutline} style={{ marginRight: 8 }} />
            API Key Configuration
          </IonTitle>
          <IonButtons slot="end">
            <IonButton onClick={onClose}>
              <IonIcon icon={closeOutline} />
            </IonButton>
          </IonButtons>
        </IonToolbar>
      </IonHeader>
      <IonContent className="ion-padding">
        <IonText color="medium">
          <p>Enter your engineer or admin API key. Keys are stored in browser localStorage.</p>
        </IonText>
        <IonItem>
          <IonLabel position="stacked">API Key</IonLabel>
          <IonInput
            type={showKey ? 'text' : 'password'}
            value={draft}
            onIonInput={e => setDraft(e.detail.value || '')}
            placeholder="64-character hex key"
            clearInput
          />
          <IonButton slot="end" fill="clear" onClick={() => setShowKey(!showKey)}>
            <IonIcon icon={showKey ? eyeOffOutline : eyeOutline} slot="icon-only" />
          </IonButton>
        </IonItem>
        <div className="ion-padding-top">
          <IonButton expand="block" color="primary" onClick={handleSave}
                     disabled={!draft.trim()}>
            <IonIcon icon={checkmarkOutline} slot="start" />
            Save Key
          </IonButton>
        </div>
      </IonContent>
    </IonModal>
  );
};
