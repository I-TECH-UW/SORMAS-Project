/*******************************************************************************
 * SORMAS® - Surveillance Outbreak Response Management & Analysis System
 * Copyright © 2016-2018 Helmholtz-Zentrum für Infektionsforschung GmbH (HZI)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/
package de.symeda.sormas.ui.samples;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

import com.vaadin.server.Page;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Notification.Type;
import com.vaadin.ui.UI;
import com.vaadin.ui.Window;

import de.symeda.sormas.api.Disease;
import de.symeda.sormas.api.FacadeProvider;
import de.symeda.sormas.api.caze.CaseClassification;
import de.symeda.sormas.api.caze.CaseDataDto;
import de.symeda.sormas.api.caze.CaseFacade;
import de.symeda.sormas.api.i18n.Captions;
import de.symeda.sormas.api.i18n.I18nProperties;
import de.symeda.sormas.api.i18n.Strings;
import de.symeda.sormas.api.sample.PathogenTestDto;
import de.symeda.sormas.api.sample.PathogenTestFacade;
import de.symeda.sormas.api.sample.PathogenTestResultType;
import de.symeda.sormas.api.sample.SampleDto;
import de.symeda.sormas.api.sample.SampleReferenceDto;
import de.symeda.sormas.api.user.UserRight;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.ui.UserProvider;
import de.symeda.sormas.ui.utils.CommitDiscardWrapperComponent;
import de.symeda.sormas.ui.utils.CommitDiscardWrapperComponent.CommitListener;
import de.symeda.sormas.ui.utils.CommitDiscardWrapperComponent.DeleteListener;
import de.symeda.sormas.ui.utils.VaadinUiUtil;

public class PathogenTestController {

	private PathogenTestFacade facade = FacadeProvider.getPathogenTestFacade();

	public PathogenTestController() {
	}

	public List<PathogenTestDto> getPathogenTestsBySample(SampleReferenceDto sampleRef) {
		return facade.getAllBySample(sampleRef);
	}

	public void create(SampleReferenceDto sampleRef, int caseSampleCount, Runnable callback,
			BiConsumer<PathogenTestResultType, Runnable> testChangedCallback) {
		PathogenTestForm createForm = new PathogenTestForm(
				FacadeProvider.getSampleFacade().getSampleByUuid(sampleRef.getUuid()), true,
				UserRight.PATHOGEN_TEST_CREATE, caseSampleCount);
		createForm.setValue(PathogenTestDto.build(sampleRef, UserProvider.getCurrent().getUser()));
		final CommitDiscardWrapperComponent<PathogenTestForm> editView = new CommitDiscardWrapperComponent<PathogenTestForm>(
				createForm, createForm.getFieldGroup());

		editView.addCommitListener(new CommitListener() {
			@Override
			public void onCommit() {
				if (!createForm.getFieldGroup().isModified()) {
					savePathogenTest(createForm.getValue(), testChangedCallback);
					callback.run();
				}
			}
		});

		VaadinUiUtil.showModalPopupWindow(editView, I18nProperties.getString(Strings.headingCreatePathogenTestResult));
	}

	public void edit(PathogenTestDto dto, int caseSampleCount, Runnable callback,
			BiConsumer<PathogenTestResultType, Runnable> testChangedCallback) {
		// get fresh data
		PathogenTestDto newDto = facade.getByUuid(dto.getUuid());

		PathogenTestForm form = new PathogenTestForm(
				FacadeProvider.getSampleFacade().getSampleByUuid(dto.getSample().getUuid()), false,
				UserRight.PATHOGEN_TEST_EDIT, caseSampleCount);
		form.setValue(newDto);
		final CommitDiscardWrapperComponent<PathogenTestForm> editView = new CommitDiscardWrapperComponent<PathogenTestForm>(
				form, form.getFieldGroup());

		Window popupWindow = VaadinUiUtil.showModalPopupWindow(editView,
				I18nProperties.getString(Strings.headingEditPathogenTestResult));

		editView.addCommitListener(new CommitListener() {
			@Override
			public void onCommit() {
				if (!form.getFieldGroup().isModified()) {
					savePathogenTest(form.getValue(), testChangedCallback);
					callback.run();
				}
			}
		});

		if (UserProvider.getCurrent().hasUserRole(UserRole.ADMIN)) {
			editView.addDeleteListener(new DeleteListener() {
				@Override
				public void onDelete() {
					FacadeProvider.getPathogenTestFacade().deletePathogenTest(dto.getUuid(),
							UserProvider.getCurrent().getUserReference().getUuid());
					UI.getCurrent().removeWindow(popupWindow);
					callback.run();
				}
			}, I18nProperties.getCaption(PathogenTestDto.I18N_PREFIX));
		}
	}

	private void savePathogenTest(PathogenTestDto dto, BiConsumer<PathogenTestResultType, Runnable> testChangedCallback) {
		SampleDto sample = FacadeProvider.getSampleFacade().getSampleByUuid(dto.getSample().getUuid());
		CaseDataDto existingCaseDto = FacadeProvider.getCaseFacade()
				.getCaseDataByUuid(sample.getAssociatedCase().getUuid());
		facade.savePathogenTest(dto);
		CaseDataDto newCaseDto = FacadeProvider.getCaseFacade().getCaseDataByUuid(sample.getAssociatedCase().getUuid());
		showSaveNotification(existingCaseDto, newCaseDto);

		Runnable caseCloningCallback = () -> {
			if (dto.getTestedDisease() != newCaseDto.getDisease() 
					&& dto.getTestResult() == PathogenTestResultType.POSITIVE
					&& dto.getTestResultVerified().booleanValue() == true) {
				buildAndShowDialogForCaseCloningWithNewDisease(newCaseDto, dto.getTestedDisease());
			}
		};
		
		// TESTEN!

		Runnable confirmCaseCallback = () -> {
			if (dto.getTestedDisease() == newCaseDto.getDisease()
					&& dto.getTestResult() == PathogenTestResultType.POSITIVE
					&& dto.getTestResultVerified().booleanValue() == true
					&& newCaseDto.getCaseClassification() != CaseClassification.CONFIRMED
					&& newCaseDto.getCaseClassification() != CaseClassification.NO_CASE) {
				buildAndShowConfirmCaseDialog(newCaseDto);
			}
		};

		if (testChangedCallback != null
				&& dto.getTestResultVerified().booleanValue() == true) {
			testChangedCallback.accept(dto.getTestResult(), () -> {
				confirmCaseCallback.run();
				caseCloningCallback.run();
			});
		} else {
			confirmCaseCallback.run();
			caseCloningCallback.run();
		}

		if (dto.getTestedDisease() != newCaseDto.getDisease() 
				&& dto.getTestResult() == PathogenTestResultType.POSITIVE
				&& dto.getTestResultVerified().booleanValue() == true) {
			buildAndShowDialogForCaseCloningWithNewDisease(newCaseDto, dto.getTestedDisease());
		}
	}

	private void buildAndShowDialogForCaseCloningWithNewDisease(CaseDataDto existingCaseDto, Disease disease) {
		VaadinUiUtil.showConfirmationPopup(
				I18nProperties.getCaption(Captions.caseCloneCaseWithNewDisease) + " " + I18nProperties.getEnumCaption(disease) + "?",
				new Label(I18nProperties.getString(Strings.messageCloneCaseWithNewDisease)),
				I18nProperties.getString(Strings.yes), I18nProperties.getString(Strings.no), 800, e -> {
					if (e.booleanValue() == true) {
						CaseDataDto clonedCase = FacadeProvider.getCaseFacade().cloneCase(existingCaseDto);
						clonedCase.setCaseClassification(CaseClassification.NOT_CLASSIFIED);
						clonedCase.setClassificationUser(null);
						clonedCase.setDisease(disease);
						clonedCase.setEpidNumber(null);
						clonedCase.setReportDate(new Date());
						FacadeProvider.getCaseFacade().saveCase(clonedCase);
					}
				});
	}

	private void buildAndShowConfirmCaseDialog(CaseDataDto caze) {
		VaadinUiUtil.showConfirmationPopup(
				I18nProperties.getCaption(Captions.caseConfirmCase),
				new Label(I18nProperties.getString(Strings.messageConfirmCaseAfterPathogenTest)),
				I18nProperties.getString(Strings.yes), I18nProperties.getString(Strings.no), 800, e -> {
					if (e.booleanValue() == true) {
						caze.setCaseClassification(CaseClassification.CONFIRMED);
						FacadeProvider.getCaseFacade().saveCase(caze);
					}
				});

	}

	private void showSaveNotification(CaseDataDto existingCaseDto, CaseDataDto newCaseDto) {
		if (existingCaseDto.getCaseClassification() != newCaseDto.getCaseClassification() && newCaseDto.getClassificationUser() == null) {
			Notification.show(String.format(I18nProperties.getString(Strings.messagePathogenTestSaved), newCaseDto.getCaseClassification().toString()), Type.TRAY_NOTIFICATION);
		} else {
			Notification.show(I18nProperties.getString(Strings.messagePathogenTestSavedShort), Type.TRAY_NOTIFICATION);
		}
	}

	public void deleteAllSelectedItems(Collection<Object> selectedRows, Runnable callback) {
		if (selectedRows.size() == 0) {
			new Notification(I18nProperties.getString(Strings.headingNoPathogenTestsSelected),
					I18nProperties.getString(Strings.messageNoPathogenTestsSelected), Type.WARNING_MESSAGE, false)
			.show(Page.getCurrent());
		} else {
			VaadinUiUtil.showDeleteConfirmationWindow(String
					.format(I18nProperties.getString(Strings.confirmationDeletePathogenTests), selectedRows.size()),
					new Runnable() {
				public void run() {
					for (Object selectedRow : selectedRows) {
						FacadeProvider.getPathogenTestFacade().deletePathogenTest(
								((PathogenTestDto) selectedRow).getUuid(), UserProvider.getCurrent().getUuid());
					}
					callback.run();
					new Notification(I18nProperties.getString(Strings.headingPathogenTestsDeleted),
							I18nProperties.getString(Strings.messagePathogenTestsDeleted),
							Type.HUMANIZED_MESSAGE, false).show(Page.getCurrent());
				}
			});
		}
	}

}
