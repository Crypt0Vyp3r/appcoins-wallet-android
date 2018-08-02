package com.asfoundation.wallet.billing;

import com.adyen.core.models.Payment;
import com.asfoundation.wallet.billing.authorization.AdyenAuthorization;
import rx.Completable;
import rx.Observable;

public interface AdyenBilling {

  Observable<AdyenAuthorization> getAuthorization();

  Completable authorize(Payment payment, String paykey);
}