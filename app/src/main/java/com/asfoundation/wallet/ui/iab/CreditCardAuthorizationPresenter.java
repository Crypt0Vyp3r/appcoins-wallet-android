package com.asfoundation.wallet.ui.iab;

import com.adyen.core.models.PaymentMethod;
import com.asfoundation.wallet.billing.AdyenBilling;
import com.asfoundation.wallet.billing.payment.Adyen;
import com.asfoundation.wallet.billing.view.card.CreditCardNavigator;
import com.asfoundation.wallet.interact.FindDefaultWalletInteract;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import io.reactivex.Scheduler;
import java.io.IOException;
import rx.Completable;
import rx.exceptions.OnErrorNotImplementedException;
import rx.subscriptions.CompositeSubscription;

/**
 * Created by franciscocalado on 30/07/2018.
 */

public class CreditCardAuthorizationPresenter {

  private final rx.Scheduler viewScheduler;
  private final CompositeSubscription disposables;
  private final Adyen adyen;
  private final AdyenBilling adyenBilling;
  private final CreditCardNavigator navigator;

  private CreditCardAuthorizationView view;
  private FindDefaultWalletInteract defaultWalletInteract;

  public CreditCardAuthorizationPresenter(CreditCardAuthorizationView view,
      FindDefaultWalletInteract defaultWalletInteract, Scheduler viewScheduler,
      CompositeSubscription disposables, Adyen adyen, AdyenBilling adyenBilling,
      CreditCardNavigator navigator) {
    this.view = view;
    this.defaultWalletInteract = defaultWalletInteract;
    this.viewScheduler = RxJavaInterop.toV1Scheduler(viewScheduler);
    this.disposables = disposables;
    this.adyen = adyen;
    this.adyenBilling = adyenBilling;
    this.navigator = navigator;
  }

  public void present() {
    disposables.add(RxJavaInterop.toV1Single(defaultWalletInteract.find())
        .observeOn(viewScheduler)
        .doOnSuccess(wallet -> view.showWalletAddress(wallet.address))
        .subscribe(wallet -> {
        }, this::showError));

    handleCancelClick();

    onViewCreatedCreatePayment();

    onViewCreatedSelectCreditCardPayment();

    onViewCreatedShowCreditCardInputView();

    onViewCreatedCheckAuthorizationActive();

    onViewCreatedCheckAuthorizationFailed();

    onViewCreatedCheckAuthorizationProcessing();

    handleAdyenCreditCardResults();

    handleAdyenUriRedirect();

    //handleAdyenUriResult();

    handleErrorDismissEvent();

    handleAdyenPaymentResult();

    handleCancel();
  }

  private void onViewCreatedShowCreditCardInputView() {
    disposables.add(adyen.getPaymentData()
        .observeOn(viewScheduler)
        .doOnSuccess(data -> {
          view.hideLoading();
          if (data.getPaymentMethod()
              .getType()
              .equals(PaymentMethod.Type.CARD)) {
            view.showCreditCardView(data.getPaymentMethod(), data.getAmount(), true,
                data.getShopperReference() != null, data.getPublicKey(), data.getGenerationTime());
          } else {
            view.showCvcView(data.getAmount(), data.getPaymentMethod());
          }
        })
        .observeOn(viewScheduler)
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void showError(Throwable throwable) {
    if (throwable instanceof IOException) {
      view.hideLoading();
      view.showNetworkError();
    } else {
      view.showNetworkError();
    }
  }

  private void onViewCreatedCreatePayment() {
    disposables.add(Completable.fromAction(() -> view.showLoading())
        .andThen(adyenBilling.getAuthorization())
        .observeOn(viewScheduler)
        //.doOnNext(payment -> view.showProduct(payment.getProduct()))
        .first(payment -> payment.isPendingAuthorization())
        .map(payment -> payment)
        //.cast(AdyenAuthorization.class)
        .flatMapCompletable(authorization -> adyen.createPayment(authorization.getSession()))
        .observeOn(viewScheduler)
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void onViewCreatedSelectCreditCardPayment() {
    disposables.add(adyen.getCreditCardPaymentService()
        .flatMapCompletable(creditCard -> adyen.selectPaymentService(creditCard))
        .observeOn(viewScheduler)
        .subscribe(() -> {
        }, throwable -> showError(throwable)));
  }

  private void onViewCreatedCheckAuthorizationActive() {
    disposables.add(adyenBilling.getAuthorization()
        .first(payment -> payment.isCompleted())
        //.doOnNext(payment -> analytics.sendAuthorizationSuccessEvent(payment))
        .observeOn(viewScheduler)
        .doOnNext(__ -> navigator.popView())
        .doOnNext(__ -> view.showSuccess())
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void onViewCreatedCheckAuthorizationFailed() {
    disposables.add(adyenBilling.getAuthorization()
        .first(payment -> payment.isFailed())
        .observeOn(viewScheduler)
        .doOnNext(__ -> navigator.popViewWithError())
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void onViewCreatedCheckAuthorizationProcessing() {
    disposables.add(adyenBilling.getAuthorization()
        .filter(payment -> payment.isProcessing())
        .observeOn(viewScheduler)
        .doOnNext(__ -> view.showLoading())
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void handleAdyenCreditCardResults() {
    disposables.add(view.creditCardDetailsEvent()
        .doOnNext(__ -> view.showLoading())
        .flatMapCompletable(details -> adyen.finishPayment(details))
        .observeOn(viewScheduler)
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  //private void handleAdyenUriResult() {
  //  view.getLifecycleEvents()
  //      .filter(event -> event.equals(View.LifecycleEvent.CREATE))
  //      .flatMap(__ -> navigator.uriResults())
  //      .flatMapCompletable(uri -> adyen.finishUri(uri))
  //      .observeOn(viewScheduler)
  //      .compose(view.bindUntilEvent(View.LifecycleEvent.DESTROY))
  //      .subscribe(__ -> {
  //      }, throwable -> showError(throwable));
  //}

  private void handleAdyenUriRedirect() {
    disposables.add(adyen.getRedirectUrl()
        .observeOn(viewScheduler)
        .doOnSuccess(redirectUrl -> {
          view.showLoading();
          //navigator.navigateToUriForResult(redirectUrl);
        })
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void handleErrorDismissEvent() {
    disposables.add(view.errorDismisses()
        //.doOnNext(__ -> popViewWithError())
        .subscribe(__ -> {
        }, throwable -> {
          throw new OnErrorNotImplementedException(throwable);
        }));
  }

  private void handleAdyenPaymentResult() {
    disposables.add(adyen.getPaymentResult()
        .flatMapCompletable(result -> {
          if (result.isProcessed()) {
            return adyenBilling.authorize(result.getPayment(), result.getPayment()
                .getPayload());
            //return billing.authorize(sku, result.getAuthorization()
            //    .getPayload());
          }
          return Completable.error(result.getError());
        })
        .observeOn(viewScheduler)
        .subscribe(() -> {
        }, throwable -> showError(throwable)));
  }

  private void handleCancel() {
    disposables.add(view.cancelEvent()
        .observeOn(viewScheduler)
        .doOnNext(__ -> {
          //analytics.sendAuthorizationCancelEvent(serviceName);
          //navigator.popView();
        })
        .subscribe(__ -> {
        }, throwable -> showError(throwable)));
  }

  private void handleCancelClick() {
    disposables.add(view.cancelEvent()
        .doOnNext(click -> close())
        .subscribe());
  }

  private void close() {
    view.close();
  }


  public void stop() {
    disposables.clear();
  }
}
